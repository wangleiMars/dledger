/*
 * Copyright 2017-2022 The DLedger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openmessaging.storage.dledger;

import com.alibaba.fastjson.JSON;
import io.openmessaging.storage.dledger.entry.DLedgerEntry;
import io.openmessaging.storage.dledger.exception.DLedgerException;
import io.openmessaging.storage.dledger.protocol.AppendEntryResponse;
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.protocol.PushEntryRequest;
import io.openmessaging.storage.dledger.protocol.PushEntryResponse;
import io.openmessaging.storage.dledger.statemachine.StateMachineCaller;
import io.openmessaging.storage.dledger.store.DLedgerMemoryStore;
import io.openmessaging.storage.dledger.store.DLedgerStore;
import io.openmessaging.storage.dledger.store.file.DLedgerMmapFileStore;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import io.openmessaging.storage.dledger.utils.Pair;
import io.openmessaging.storage.dledger.utils.PreConditions;
import io.openmessaging.storage.dledger.utils.Quota;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DLedgerEntryPusher {

    private static Logger logger = LoggerFactory.getLogger(DLedgerEntryPusher.class);
    //多副本相关配置。
    private DLedgerConfig dLedgerConfig;
    //存储实现类。
    private DLedgerStore dLedgerStore;
    //节点状态机。
    private final MemberState memberState;

    private DLedgerRpcService dLedgerRpcService;
    //每个节点基于投票轮次的当前水位线标记。键值为投票轮次，值为 ConcurrentMap<String/** 节点id*/, Long/** 节点对应的日志序号*/>。
    private Map<Long/**投票轮次*/, ConcurrentMap<String/** 节点id*/, Long/** 节点对应的日志序号*/>> peerWaterMarksByTerm = new ConcurrentHashMap<>();
    //用于存放追加请求的响应结果(Future模式)。
    private Map<Long, ConcurrentMap<Long , TimeoutFuture<AppendEntryResponse>>> pendingAppendResponsesByTerm = new ConcurrentHashMap<>();
    //从节点上开启的线程，用于接收主节点的 push 请求（append、commit、append）。
    private EntryHandler entryHandler;
    //主节点上的追加请求投票器。
    private QuorumAckChecker quorumAckChecker;
    //主节点日志请求转发器，向从节点复制消息等。
    private Map<String, EntryDispatcher> dispatcherMap = new HashMap<>();

    private Optional<StateMachineCaller> fsmCaller;

    public DLedgerEntryPusher(DLedgerConfig dLedgerConfig, MemberState memberState, DLedgerStore dLedgerStore,
        DLedgerRpcService dLedgerRpcService) {
        this.dLedgerConfig = dLedgerConfig;
        this.memberState = memberState;
        this.dLedgerStore = dLedgerStore;
        this.dLedgerRpcService = dLedgerRpcService;
        for (String peer : memberState.getPeerMap().keySet()) {
            if (!peer.equals(memberState.getSelfId())) {
                dispatcherMap.put(peer, new EntryDispatcher(peer, logger));
            }
        }
        this.entryHandler = new EntryHandler(logger);
        this.quorumAckChecker = new QuorumAckChecker(logger);
        this.fsmCaller = Optional.empty();
    }

    public void startup() {
        entryHandler.start();
        quorumAckChecker.start();
        for (EntryDispatcher dispatcher : dispatcherMap.values()) {
            dispatcher.start();
        }
    }

    public void shutdown() {
        entryHandler.shutdown();
        quorumAckChecker.shutdown();
        for (EntryDispatcher dispatcher : dispatcherMap.values()) {
            dispatcher.shutdown();
        }
    }

    public void registerStateMachine(final Optional<StateMachineCaller> fsmCaller) {
        this.fsmCaller = fsmCaller;
    }

    public CompletableFuture<PushEntryResponse> handlePush(PushEntryRequest request) throws Exception {
        return entryHandler.handlePush(request);
    }

    private void checkTermForWaterMark(long term, String env) {
        if (!peerWaterMarksByTerm.containsKey(term)) {
            logger.info("Initialize the watermark in {} for term={}", env, term);
            ConcurrentMap<String, Long> waterMarks = new ConcurrentHashMap<>();
            for (String peer : memberState.getPeerMap().keySet()) {
                waterMarks.put(peer, -1L);
            }
            peerWaterMarksByTerm.putIfAbsent(term, waterMarks);
        }
    }

    private void checkTermForPendingMap(long term, String env) {
        if (!pendingAppendResponsesByTerm.containsKey(term)) {
            logger.info("Initialize the pending append map in {} for term={}", env, term);
            pendingAppendResponsesByTerm.putIfAbsent(term, new ConcurrentHashMap<>());
        }
    }

    private void updatePeerWaterMark(long term, String peerId, long index) {
        synchronized (peerWaterMarksByTerm) {
            checkTermForWaterMark(term, "updatePeerWaterMark");
            if (peerWaterMarksByTerm.get(term).get(peerId) < index) {
                peerWaterMarksByTerm.get(term).put(peerId, index);
            }
        }
    }

    public long getPeerWaterMark(long term, String peerId) {
        synchronized (peerWaterMarksByTerm) {
            checkTermForWaterMark(term, "getPeerWaterMark");
            return peerWaterMarksByTerm.get(term).get(peerId);
        }
    }

    public boolean isPendingFull(long currTerm) {
        checkTermForPendingMap(currTerm, "isPendingFull");
        return pendingAppendResponsesByTerm.get(currTerm).size() > dLedgerConfig.getMaxPendingRequestsNum();
    }

    public CompletableFuture<AppendEntryResponse> waitAck(DLedgerEntry entry, boolean isBatchWait) {
        updatePeerWaterMark(entry.getTerm(), memberState.getSelfId(), entry.getIndex());//更新当前节点的 push 水位线。
        if (memberState.getPeerMap().size() == 1) {//如果集群的节点个数为1，无需转发，直接返回成功结果。
            AppendEntryResponse response = new AppendEntryResponse();
            response.setGroup(memberState.getGroup());
            response.setLeaderId(memberState.getSelfId());
            response.setIndex(entry.getIndex());
            response.setTerm(entry.getTerm());
            response.setPos(entry.getPos());
            if (isBatchWait) {
                return BatchAppendFuture.newCompletedFuture(entry.getPos(), response);
            }
            return AppendFuture.newCompletedFuture(entry.getPos(), response);
        } else {
            checkTermForPendingMap(entry.getTerm(), "waitAck");
            AppendFuture<AppendEntryResponse> future;
            if (isBatchWait) {
                future = new BatchAppendFuture<>(dLedgerConfig.getMaxWaitAckTimeMs());
            } else {
                future = new AppendFuture<>(dLedgerConfig.getMaxWaitAckTimeMs());
            }
            future.setPos(entry.getPos());
            //将构建的 Future 放入等待结果集合中
            CompletableFuture<AppendEntryResponse> old = pendingAppendResponsesByTerm.get(entry.getTerm()).put(entry.getIndex(), future);
            if (old != null) {
                logger.warn("[MONITOR] get old wait at index={}", entry.getIndex());
            }
            return future;
        }
    }

    public void wakeUpDispatchers() {
        for (EntryDispatcher dispatcher : dispatcherMap.values()) {
            dispatcher.wakeup();
        }
    }

    /**
     *
     * Complete the TimeoutFuture in pendingAppendResponsesByTerm (CurrentTerm, index).
     * Called by statemachineCaller when a committed entry (CurrentTerm, index) was applying to statemachine done.
     *
     * @return true if complete success
     */
    public boolean completeResponseFuture(final long index) {
        final long term = this.memberState.currTerm();
        final Map<Long, TimeoutFuture<AppendEntryResponse>> responses = this.pendingAppendResponsesByTerm.get(term);
        if (responses != null) {
            CompletableFuture<AppendEntryResponse> future = responses.remove(index);
            if (future != null && !future.isDone()) {
                logger.info("Complete future, term {}, index {}", term, index);
                AppendEntryResponse response = new AppendEntryResponse();
                response.setGroup(this.memberState.getGroup());
                response.setTerm(term);
                response.setIndex(index);
                response.setLeaderId(this.memberState.getSelfId());
                response.setPos(((AppendFuture) future).getPos());
                future.complete(response);
                return true;
            }
        }
        return false;
    }

    /**
     * Check responseFutures timeout from {beginIndex} in currentTerm
     */
    public void checkResponseFuturesTimeout(final long beginIndex) {
        final long term = this.memberState.currTerm();
        final Map<Long, TimeoutFuture<AppendEntryResponse>> responses = this.pendingAppendResponsesByTerm.get(term);
        if (responses != null) {
            for (long i = beginIndex; i < Integer.MAX_VALUE; i++) {
                TimeoutFuture<AppendEntryResponse> future = responses.get(i);
                if (future == null) {
                    break;
                } else if (future.isTimeOut()) {
                    AppendEntryResponse response = new AppendEntryResponse();
                    response.setGroup(memberState.getGroup());
                    response.setCode(DLedgerResponseCode.WAIT_QUORUM_ACK_TIMEOUT.getCode());
                    response.setTerm(term);
                    response.setIndex(i);
                    response.setLeaderId(memberState.getSelfId());
                    future.complete(response);
                } else {
                    break;
                }
            }
        }
    }

    /**
     * Check responseFutures elapsed before {endIndex} in currentTerm
     */
    private void checkResponseFuturesElapsed(final long endIndex) {
        final long currTerm = this.memberState.currTerm();
        final Map<Long, TimeoutFuture<AppendEntryResponse>> responses = this.pendingAppendResponsesByTerm.get(currTerm);
        for (Map.Entry<Long, TimeoutFuture<AppendEntryResponse>> futureEntry : responses.entrySet()) {
            if (futureEntry.getKey() < endIndex) {
                AppendEntryResponse response = new AppendEntryResponse();
                response.setGroup(memberState.getGroup());
                response.setTerm(currTerm);
                response.setIndex(futureEntry.getKey());
                response.setLeaderId(memberState.getSelfId());
                response.setPos(((AppendFuture) futureEntry.getValue()).getPos());
                futureEntry.getValue().complete(response);
                responses.remove(futureEntry.getKey());
            }
        }
    }

    /**
     *
     * @param term 主节点当前的投票轮次。
     * @param committedIndex 主节点发送日志复制请求时的已提交日志序号。
     */
    private void updateCommittedIndex(final long term, final long committedIndex) {
        dLedgerStore.updateCommittedIndex(term, committedIndex);
        this.fsmCaller.ifPresent(caller -> caller.onCommitted(committedIndex));
    }

    /**
     * 日志复制投票器，一个日志写请求只有得到集群内的的大多数节点的响应，日志才会被提交。
     * This thread will check the quorum index and complete the pending requests.
     */
    private class QuorumAckChecker extends ShutdownAbleThread {
        /**
         * 上次打印水位线的时间戳，单位为毫秒。
         */
        private long lastPrintWatermarkTimeMs = System.currentTimeMillis();
        /**
         * 上次检测泄漏的时间戳，单位为毫秒。
         */
        private long lastCheckLeakTimeMs = System.currentTimeMillis();
        /**
         * 已投票仲裁的日志序号。
         */
        private long lastQuorumIndex = -1;

        public QuorumAckChecker(Logger logger) {
            super("QuorumAckChecker-" + memberState.getSelfId(), logger);
        }

        @Override
        public void doWork() {
            try {
                /**
                 * 如果离上一次打印 watermak 的时间超过3s，则打印一下当前的 term、ledgerBegin、ledgerEnd、committed、peerWaterMarksByTerm 这些数据日志。
                 */
                if (DLedgerUtils.elapsed(lastPrintWatermarkTimeMs) > 3000) {
                    if (DLedgerEntryPusher.this.fsmCaller.isPresent()) {
                        final long lastAppliedIndex = DLedgerEntryPusher.this.fsmCaller.get().getLastAppliedIndex();
                        logger.info("[{}][{}] term={} ledgerBegin={} ledgerEnd={} committed={} watermarks={} appliedIndex={}",
                            memberState.getSelfId(), memberState.getRole(), memberState.currTerm(), dLedgerStore.getLedgerBeginIndex(), dLedgerStore.getLedgerEndIndex(), dLedgerStore.getCommittedIndex(), JSON.toJSONString(peerWaterMarksByTerm), lastAppliedIndex);
                    } else {
                        logger.info("[{}][{}] term={} ledgerBegin={} ledgerEnd={} committed={} watermarks={}",
                            memberState.getSelfId(), memberState.getRole(), memberState.currTerm(), dLedgerStore.getLedgerBeginIndex(), dLedgerStore.getLedgerEndIndex(), dLedgerStore.getCommittedIndex(), JSON.toJSONString(peerWaterMarksByTerm));
                    }
                    lastPrintWatermarkTimeMs = System.currentTimeMillis();
                }
                if (!memberState.isLeader()) {
                    waitForRunning(1);
                    return;
                }
                long currTerm = memberState.currTerm();
                checkTermForPendingMap(currTerm, "QuorumAckChecker");
                checkTermForWaterMark(currTerm, "QuorumAckChecker");
                /**
                 * 清理pendingAppendResponsesByTerm、peerWaterMarksByTerm 中本次投票轮次的数据，避免一些不必要的内存使用。
                 */
                if (pendingAppendResponsesByTerm.size() > 1) {
                    for (Long term : pendingAppendResponsesByTerm.keySet()) {
                        if (term == currTerm) {
                            continue;
                        }
                        for (Map.Entry<Long, TimeoutFuture<AppendEntryResponse>> futureEntry : pendingAppendResponsesByTerm.get(term).entrySet()) {
                            AppendEntryResponse response = new AppendEntryResponse();
                            response.setGroup(memberState.getGroup());
                            response.setIndex(futureEntry.getKey());
                            response.setCode(DLedgerResponseCode.TERM_CHANGED.getCode());
                            response.setLeaderId(memberState.getLeaderId());
                            logger.info("[TermChange] Will clear the pending response index={} for term changed from {} to {}", futureEntry.getKey(), term, currTerm);
                            futureEntry.getValue().complete(response);
                        }
                        pendingAppendResponsesByTerm.remove(term);
                    }
                }
                if (peerWaterMarksByTerm.size() > 1) {
                    for (Long term : peerWaterMarksByTerm.keySet()) {
                        if (term == currTerm) {
                            continue;
                        }
                        logger.info("[TermChange] Will clear the watermarks for term changed from {} to {}", term, currTerm);
                        peerWaterMarksByTerm.remove(term);
                    }
                }
                /**
                 * 根据各个从节点反馈的进度，进行仲裁，确定已提交序号。
                 */
                Map<String, Long> peerWaterMarks = peerWaterMarksByTerm.get(currTerm);
                List<Long> sortedWaterMarks = peerWaterMarks.values()
                    .stream()
                    .sorted(Comparator.reverseOrder())//反序
                    .collect(Collectors.toList());
                long quorumIndex = sortedWaterMarks.get(sortedWaterMarks.size() / 2);
                final Optional<StateMachineCaller> fsmCaller = DLedgerEntryPusher.this.fsmCaller;
                if (fsmCaller.isPresent()) {
                    // If there exist statemachine
                    DLedgerEntryPusher.this.dLedgerStore.updateCommittedIndex(currTerm, quorumIndex);
                    final StateMachineCaller caller = fsmCaller.get();
                    caller.onCommitted(quorumIndex);

                    // Check elapsed
                    if (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000) {
                        updatePeerWaterMark(currTerm, memberState.getSelfId(), dLedgerStore.getLedgerEndIndex());
                        checkResponseFuturesElapsed(caller.getLastAppliedIndex());
                        lastCheckLeakTimeMs = System.currentTimeMillis();
                    }

                    if (quorumIndex == this.lastQuorumIndex) {
                        waitForRunning(1);
                    }
                } else {
                    /**
                     * 处理 quorumIndex 之前的挂起请求
                     */
                    dLedgerStore.updateCommittedIndex(currTerm, quorumIndex);
                    ConcurrentMap<Long, TimeoutFuture<AppendEntryResponse>> responses = pendingAppendResponsesByTerm.get(currTerm);
                    boolean needCheck = false;
                    int ackNum = 0;
                    for (Long i = quorumIndex; i > lastQuorumIndex; i--) {//从 quorumIndex 开始处理，没处理一条，该序号减一，直到大于0或主动退出
                        try {
                            CompletableFuture<AppendEntryResponse> future = responses.remove(i);
                            if (future == null) {//如果未找到挂起请求，说明前面挂起的请求已经全部处理完毕，准备退出，退出之前再 设置 needCheck 的值，
                                needCheck = true;
                                break;
                            } else if (!future.isDone()) {
                                AppendEntryResponse response = new AppendEntryResponse();
                                response.setGroup(memberState.getGroup());
                                response.setTerm(currTerm);
                                response.setIndex(i);
                                response.setLeaderId(memberState.getSelfId());
                                response.setPos(((AppendFuture) future).getPos());
                                future.complete(response);
                            }
                            ackNum++;//本次确认的数量。
                        } catch (Throwable t) {
                            logger.error("Error in ack to index={} term={}", i, currTerm, t);
                        }
                    }
                    //如果本次确认的个数为0，则尝试去判断超过该仲裁序号的请求，是否已经超时，如果已超时，则返回超时响应结果。
                    if (ackNum == 0) {
                        checkResponseFuturesTimeout(quorumIndex + 1);
                        waitForRunning(1);
                    }
                    //检查是否发送泄漏。其判断泄漏的依据是如果挂起的请求的日志序号小于已提交的序号，则移除。
                    if (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000 || needCheck) {
                        updatePeerWaterMark(currTerm, memberState.getSelfId(), dLedgerStore.getLedgerEndIndex());
                        checkResponseFuturesElapsed(quorumIndex);
                        lastCheckLeakTimeMs = System.currentTimeMillis();
                    }
                }
                //一次日志仲裁就结束了，最后更新 lastQuorumIndex 为本次仲裁的的新的提交值。
                lastQuorumIndex = quorumIndex;
            } catch (Throwable t) {
                DLedgerEntryPusher.logger.error("Error in {}", getName(), t);
                DLedgerUtils.sleep(100);
            }
        }
    }

    /**
     * This thread will be activated by the leader.
     * This thread will push the entry to follower(identified by peerId) and update the completed pushed index to index map.
     * Should generate a single thread for each peer.
     * The push has 4 types:
     *   APPEND : append the entries to the follower
     *   COMPARE : if the leader changes, the new leader should compare its entries to follower's
     *   TRUNCATE : if the leader finished comparing by an index, the leader will send a request to truncate the follower's ledger
     *   COMMIT: usually, the leader will attach the committed index with the APPEND request, but if the append requests are few and scattered,
     *           the leader will send a pure request to inform the follower of committed index.
     *
     *   The common transferring between these types are as following:
     *
     *   COMPARE ---- TRUNCATE ---- APPEND ---- COMMIT
     *   ^                             |
     *   |---<-----<------<-------<----|
     * COMPARE
     * 如果 Leader 发生变化，新的 Leader 需要与他的从节点的日志条目进行比较，以便截断从节点多余的数据。
     * TRUNCATE
     * 如果 Leader 通过索引完成日志对比，则 Leader 将发送 TRUNCATE 给它的从节点。
     * APPEND
     * 将日志条目追加到从节点。
     * COMMIT
     * 通常，leader 会将提交的索引附加到 append 请求，但是如果 append 请求很少且分散，leader 将发送一个单独的请求来通知从节点提交的索引。
     *
     */
    private class EntryDispatcher extends ShutdownAbleThread {
        //向从节点发送命令的类型，可选值：PushEntryRequest.Type.COMPARE、TRUNCATE、APPEND、COMMIT，
        private AtomicReference<PushEntryRequest.Type> type = new AtomicReference<>(PushEntryRequest.Type.COMPARE);
        //上一次发送提交类型的时间戳。
        private long lastPushCommitTimeMs = -1;
        //目标节点ID。
        private String peerId;
        //已完成比较的日志序号。
        private long compareIndex = -1;
        //已写入的日志序号。
        private long writeIndex = -1;
        //允许的最大挂起日志数量。
        private int maxPendingSize = 1000;
        //Leader 节点当前的投票轮次。
        private long term = -1;
        //Leader 节点ID。
        private String leaderId = null;
        //上次检测泄漏的时间，所谓的泄漏，就是看挂起的日志请求数量是否查过了 maxPendingSize 。
        private long lastCheckLeakTimeMs = System.currentTimeMillis();
        //记录日志的挂起时间，key：日志的序列(entryIndex)，value：挂起时间戳。
        private ConcurrentMap<Long, Long> pendingMap = new ConcurrentHashMap<>();
        private ConcurrentMap<Long, Pair<Long, Integer>> batchPendingMap = new ConcurrentHashMap<>();
        private PushEntryRequest batchAppendEntryRequest = new PushEntryRequest();
        //配额。
        private Quota quota = new Quota(dLedgerConfig.getPeerPushQuota());

        public EntryDispatcher(String peerId, Logger logger) {
            super("EntryDispatcher-" + memberState.getSelfId() + "-" + peerId, logger);
            this.peerId = peerId;
        }

        private boolean checkAndFreshState() {
            //如果节点的状态不是主节点，则直接返回 false。则结束 本次 doWork 方法。因为只有主节点才需要向从节点转发日志。
            if (!memberState.isLeader()) {
                return false;
            }
            //如果当前节点状态是主节点，但当前的投票轮次与状态机轮次或 leaderId 还未设置，
            // 或 leaderId 与状态机的 leaderId 不相等，这种情况通常是集群触发了重新选举，设置其term、leaderId与状态机同步，即将发送COMPARE 请求。
            if (term != memberState.currTerm() || leaderId == null || !leaderId.equals(memberState.getLeaderId())) {
                synchronized (memberState) {
                    if (!memberState.isLeader()) {
                        return false;
                    }
                    PreConditions.check(memberState.getSelfId().equals(memberState.getLeaderId()), DLedgerResponseCode.UNKNOWN);
                    term = memberState.currTerm();
                    leaderId = memberState.getSelfId();
                    changeState(-1, PushEntryRequest.Type.COMPARE);
                }
            }
            return true;
        }

        private PushEntryRequest buildPushRequest(DLedgerEntry entry, PushEntryRequest.Type target) {
            PushEntryRequest request = new PushEntryRequest();
            request.setGroup(memberState.getGroup());
            request.setRemoteId(peerId);
            request.setLeaderId(leaderId);
            request.setTerm(term);
            request.setEntry(entry);
            request.setType(target);
            request.setCommitIndex(dLedgerStore.getCommittedIndex());
            return request;
        }

        private void resetBatchAppendEntryRequest() {
            batchAppendEntryRequest.setGroup(memberState.getGroup());
            batchAppendEntryRequest.setRemoteId(peerId);
            batchAppendEntryRequest.setLeaderId(leaderId);
            batchAppendEntryRequest.setTerm(term);
            batchAppendEntryRequest.setType(PushEntryRequest.Type.APPEND);
            batchAppendEntryRequest.clear();
        }

        /**
         * 检测配额，如果超过配额，会进行一定的限流
         * 首先触发条件：append 挂起请求数已超过最大允许挂起数；基于文件存储并主从差异超过300m，可通过 peerPushThrottlePoint 配置。
         * 每秒追加的日志超过 20m(可通过 peerPushQuota 配置)，则会 sleep 1s中后再追加。
         * @param entry
         */
        private void checkQuotaAndWait(DLedgerEntry entry) {
            if (dLedgerStore.getLedgerEndIndex() - entry.getIndex() <= maxPendingSize) {
                return;
            }
            if (dLedgerStore instanceof DLedgerMemoryStore) {
                return;
            }
            DLedgerMmapFileStore mmapFileStore = (DLedgerMmapFileStore) dLedgerStore;
            if (mmapFileStore.getDataFileList().getMaxWrotePosition() - entry.getPos() < dLedgerConfig.getPeerPushThrottlePoint()) {
                return;
            }
            quota.sample(entry.getSize());
            if (quota.validateNow()) {
                long leftNow = quota.leftNow();
                logger.warn("[Push-{}]Quota exhaust, will sleep {}ms", peerId, leftNow);
                DLedgerUtils.sleep(leftNow);
            }
        }
        private void doAppendInner(long index) throws Exception {
            DLedgerEntry entry = getDLedgerEntryForAppend(index);
            if (null == entry) {
                return;
            }
            checkQuotaAndWait(entry);
            PushEntryRequest request = buildPushRequest(entry, PushEntryRequest.Type.APPEND);
            CompletableFuture<PushEntryResponse> responseFuture = dLedgerRpcService.push(request);
            //用 pendingMap 记录待追加的日志的发送时间，用于发送端判断是否超时的一个依据。
            pendingMap.put(index, System.currentTimeMillis());
            responseFuture.whenComplete((x, ex) -> {
                try {
                    PreConditions.check(ex == null, DLedgerResponseCode.UNKNOWN);
                    DLedgerResponseCode responseCode = DLedgerResponseCode.valueOf(x.getCode());
                    switch (responseCode) {
                        case SUCCESS:
                            pendingMap.remove(x.getIndex());
                            //更新已成功追加的日志序号(按投票轮次组织，并且每个从服务器一个键值对)。
                            updatePeerWaterMark(x.getTerm(), peerId, x.getIndex());
                            //唤醒 quorumAckChecker 线程(主要用于仲裁 append 结果)
                            quorumAckChecker.wakeup();
                            break;
                        case INCONSISTENT_STATE:
                            //Push 请求出现状态不一致情况，将发送 COMPARE 请求，来对比主从节点的数据是否一致。
                            logger.info("[Push-{}]Get INCONSISTENT_STATE when push index={} term={}", peerId, x.getIndex(), x.getTerm());
                            changeState(-1, PushEntryRequest.Type.COMPARE);
                            break;
                        default:
                            logger.warn("[Push-{}]Get error response code {} {}", peerId, responseCode, x.baseInfo());
                            break;
                    }
                } catch (Throwable t) {
                    logger.error("", t);
                }
            });
            lastPushCommitTimeMs = System.currentTimeMillis();
        }

        /**
         * 首先根据序号查询出日志。
         * @param index
         * @return
         */
        private DLedgerEntry getDLedgerEntryForAppend(long index) {
            DLedgerEntry entry;
            try {
                entry = dLedgerStore.get(index);
            } catch (DLedgerException e) {
                //  Do compare, in case the ledgerBeginIndex get refreshed.
                if (DLedgerResponseCode.INDEX_LESS_THAN_LOCAL_BEGIN.equals(e.getCode())) {
                    logger.info("[Push-{}]Get INDEX_LESS_THAN_LOCAL_BEGIN when requested index is {}, try to compare", peerId, index);
                    changeState(-1, PushEntryRequest.Type.COMPARE);
                    return null;
                }
                throw e;
            }
            PreConditions.check(entry != null, DLedgerResponseCode.UNKNOWN, "writeIndex=%d", index);
            return entry;
        }

        private void doCommit() throws Exception {
            if (DLedgerUtils.elapsed(lastPushCommitTimeMs) > 1000) {//如果上一次单独发送 commit 的请求时间与当前时间相隔低于 1s，放弃本次提交请求。
                PushEntryRequest request = buildPushRequest(null, PushEntryRequest.Type.COMMIT);
                dLedgerRpcService.push(request);
                lastPushCommitTimeMs = System.currentTimeMillis();
            }
        }

        /**
         * 检查并追加请求
         * @throws Exception
         */
        private void doCheckAppendResponse() throws Exception {
            long peerWaterMark = getPeerWaterMark(term, peerId);//获取已成功 append 的序号。
            Long sendTimeMs = pendingMap.get(peerWaterMark + 1);
            /**
             * 从挂起的请求队列中获取下一条的发送时间，如果不为空并去超过了 append 的超时时间，
             * 则再重新发送 append 请求，最大超时时间默认为 1s，可以通过 maxPushTimeOutMs 来改变默认值。
             */
            if (sendTimeMs != null && System.currentTimeMillis() - sendTimeMs > dLedgerConfig.getMaxPushTimeOutMs()) {
                logger.warn("[Push-{}]Retry to push entry at {}", peerId, peerWaterMark + 1);
                doAppendInner(peerWaterMark + 1);
            }
        }

        private void doAppend() throws Exception {
            while (true) {
                if (!checkAndFreshState()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.APPEND) {//如果请求类型不为 APPEND，则退出，
                    break;
                }
                /**
                 * writeIndex 表示当前追加到从该节点的序号，通常情况下主节点向从节点发送 append 请求时，
                 * 会附带主节点的已提交指针，但如何 append 请求发不那么频繁，writeIndex 大于 leaderEndIndex 时
                 * （由于pending请求超过其 pending 请求的队列长度（默认为1w)，时，会阻止数据的追加，此时有可能出现
                 * writeIndex 大于 leaderEndIndex 的情况，此时单独发送 COMMIT 请求。
                 */
                if (writeIndex > dLedgerStore.getLedgerEndIndex()) {
                    doCommit();
                    doCheckAppendResponse();
                    break;
                }
                /**
                 * 检测 pendingMap(挂起的请求数量)是否发送泄漏，即挂起队列中容量是否超过允许的最大挂起阀值。
                 * 获取当前节点关于本轮次的当前水位线(已成功 append 请求的日志序号)，如果发现正在挂起请求的日志序号小于水位线，则丢弃。
                 */
                if (pendingMap.size() >= maxPendingSize || (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000)) {
                    long peerWaterMark = getPeerWaterMark(term, peerId);
                    for (Long index : pendingMap.keySet()) {
                        if (index < peerWaterMark) {
                            pendingMap.remove(index);
                        }
                    }
                    lastCheckLeakTimeMs = System.currentTimeMillis();
                }
                if (pendingMap.size() >= maxPendingSize) {//如果挂起的请求（等待从节点追加结果）大于 maxPendingSize 时，检查并追加一次 append 请求。
                    doCheckAppendResponse();
                    break;
                }
                doAppendInner(writeIndex);
                writeIndex++;
            }
        }

        private void sendBatchAppendEntryRequest() throws Exception {
            batchAppendEntryRequest.setCommitIndex(dLedgerStore.getCommittedIndex());
            CompletableFuture<PushEntryResponse> responseFuture = dLedgerRpcService.push(batchAppendEntryRequest);
            batchPendingMap.put(batchAppendEntryRequest.getFirstEntryIndex(), new Pair<>(System.currentTimeMillis(), batchAppendEntryRequest.getCount()));
            responseFuture.whenComplete((x, ex) -> {
                try {
                    PreConditions.check(ex == null, DLedgerResponseCode.UNKNOWN);
                    DLedgerResponseCode responseCode = DLedgerResponseCode.valueOf(x.getCode());
                    switch (responseCode) {
                        case SUCCESS:
                            batchPendingMap.remove(x.getIndex());
                            updatePeerWaterMark(x.getTerm(), peerId, x.getIndex() + x.getCount() - 1);
                            break;
                        case INCONSISTENT_STATE:
                            logger.info("[Push-{}]Get INCONSISTENT_STATE when batch push index={} term={}", peerId, x.getIndex(), x.getTerm());
                            changeState(-1, PushEntryRequest.Type.COMPARE);
                            break;
                        default:
                            logger.warn("[Push-{}]Get error response code {} {}", peerId, responseCode, x.baseInfo());
                            break;
                    }
                } catch (Throwable t) {
                    logger.error("", t);
                }
            });
            lastPushCommitTimeMs = System.currentTimeMillis();
            batchAppendEntryRequest.clear();
        }

        private void doBatchAppendInner(long index) throws Exception {
            DLedgerEntry entry = getDLedgerEntryForAppend(index);
            if (null == entry) {
                return;
            }
            batchAppendEntryRequest.addEntry(entry);
            if (batchAppendEntryRequest.getTotalSize() >= dLedgerConfig.getMaxBatchPushSize()) {
                sendBatchAppendEntryRequest();
            }
        }

        private void doCheckBatchAppendResponse() throws Exception {
            long peerWaterMark = getPeerWaterMark(term, peerId);
            Pair pair = batchPendingMap.get(peerWaterMark + 1);
            if (pair != null && System.currentTimeMillis() - (long) pair.getKey() > dLedgerConfig.getMaxPushTimeOutMs()) {
                long firstIndex = peerWaterMark + 1;
                long lastIndex = firstIndex + (int) pair.getValue() - 1;
                logger.warn("[Push-{}]Retry to push entry from {} to {}", peerId, firstIndex, lastIndex);
                batchAppendEntryRequest.clear();
                for (long i = firstIndex; i <= lastIndex; i++) {
                    DLedgerEntry entry = dLedgerStore.get(i);
                    batchAppendEntryRequest.addEntry(entry);
                }
                sendBatchAppendEntryRequest();
            }
        }

        private void doBatchAppend() throws Exception {
            while (true) {
                if (!checkAndFreshState()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.APPEND) {
                    break;
                }
                if (writeIndex > dLedgerStore.getLedgerEndIndex()) {
                    if (batchAppendEntryRequest.getCount() > 0) {
                        sendBatchAppendEntryRequest();
                    }
                    doCommit();
                    doCheckBatchAppendResponse();
                    break;
                }
                if (batchPendingMap.size() >= maxPendingSize || (DLedgerUtils.elapsed(lastCheckLeakTimeMs) > 1000)) {
                    long peerWaterMark = getPeerWaterMark(term, peerId);
                    for (Map.Entry<Long, Pair<Long, Integer>> entry : batchPendingMap.entrySet()) {
                        if (entry.getKey() + entry.getValue().getValue() - 1 <= peerWaterMark) {
                            batchPendingMap.remove(entry.getKey());
                        }
                    }
                    lastCheckLeakTimeMs = System.currentTimeMillis();
                }
                if (batchPendingMap.size() >= maxPendingSize) {
                    doCheckBatchAppendResponse();
                    break;
                }
                doBatchAppendInner(writeIndex);
                writeIndex++;
            }
        }

        private void doTruncate(long truncateIndex) throws Exception {
            PreConditions.check(type.get() == PushEntryRequest.Type.TRUNCATE, DLedgerResponseCode.UNKNOWN);
            DLedgerEntry truncateEntry = dLedgerStore.get(truncateIndex);
            PreConditions.check(truncateEntry != null, DLedgerResponseCode.UNKNOWN);
            logger.info("[Push-{}]Will push data to truncate truncateIndex={} pos={}", peerId, truncateIndex, truncateEntry.getPos());
            PushEntryRequest truncateRequest = buildPushRequest(truncateEntry, PushEntryRequest.Type.TRUNCATE);
            PushEntryResponse truncateResponse = dLedgerRpcService.push(truncateRequest).get(3, TimeUnit.SECONDS);
            PreConditions.check(truncateResponse != null, DLedgerResponseCode.UNKNOWN, "truncateIndex=%d", truncateIndex);
            PreConditions.check(truncateResponse.getCode() == DLedgerResponseCode.SUCCESS.getCode(), DLedgerResponseCode.valueOf(truncateResponse.getCode()), "truncateIndex=%d", truncateIndex);
            lastPushCommitTimeMs = System.currentTimeMillis();
            changeState(truncateIndex, PushEntryRequest.Type.APPEND);
        }

        private synchronized void changeState(long index, PushEntryRequest.Type target) {
            logger.info("[Push-{}]Change state from {} to {} at {}", peerId, type.get(), target, index);
            switch (target) {
                //如果将目标类型设置为 append，则重置 compareIndex ，并设置 writeIndex 为当前 index 加1。
                case APPEND:
                    compareIndex = -1;
                    updatePeerWaterMark(term, peerId, index);
                    quorumAckChecker.wakeup();
                    writeIndex = index + 1;
                    if (dLedgerConfig.isEnableBatchPush()) {
                        resetBatchAppendEntryRequest();
                    }
                    break;
                case COMPARE://如果将目标类型设置为 COMPARE，则重置 compareIndex 为负一，接下将向各个从节点发送 COMPARE 请求类似，并清除已挂起的请求。
                    if (this.type.compareAndSet(PushEntryRequest.Type.APPEND, PushEntryRequest.Type.COMPARE)) {
                        compareIndex = -1;
                        if (dLedgerConfig.isEnableBatchPush()) {
                            batchPendingMap.clear();
                        } else {
                            pendingMap.clear();
                        }
                    }
                    break;
                case TRUNCATE://如果将目标类型设置为 TRUNCATE，则重置 compareIndex 为负一。
                    compareIndex = -1;
                    break;
                default:
                    break;
            }
            type.set(target);
        }

        private void doCompare() throws Exception {
            while (true) {
                if (!checkAndFreshState()) {
                    break;
                }
                if (type.get() != PushEntryRequest.Type.COMPARE
                    && type.get() != PushEntryRequest.Type.TRUNCATE) {
                    break;
                }
                //如果已比较索引 和 ledgerEndIndex 都为 -1 ，表示一个新的 DLedger 集群，则直接跳出。
                if (compareIndex == -1 && dLedgerStore.getLedgerEndIndex() == -1) {
                    break;
                }
                //revise the compareIndex
                if (compareIndex == -1) {
                    compareIndex = dLedgerStore.getLedgerEndIndex();
                    logger.info("[Push-{}][DoCompare] compareIndex=-1 means start to compare", peerId);
                } else if (compareIndex > dLedgerStore.getLedgerEndIndex() || compareIndex < dLedgerStore.getLedgerBeginIndex()) {
                    logger.info("[Push-{}][DoCompare] compareIndex={} out of range {}-{}", peerId, compareIndex, dLedgerStore.getLedgerBeginIndex(), dLedgerStore.getLedgerEndIndex());
                    compareIndex = dLedgerStore.getLedgerEndIndex();
                }

                DLedgerEntry entry = dLedgerStore.get(compareIndex);
                PreConditions.check(entry != null, DLedgerResponseCode.INTERNAL_ERROR, "compareIndex=%d", compareIndex);
                PushEntryRequest request = buildPushRequest(entry, PushEntryRequest.Type.COMPARE);
                //并向从节点发起 COMPARE 请求，其超时时间为 3s
                CompletableFuture<PushEntryResponse> responseFuture = dLedgerRpcService.push(request);
                PushEntryResponse response = responseFuture.get(3, TimeUnit.SECONDS);
                PreConditions.check(response != null, DLedgerResponseCode.INTERNAL_ERROR, "compareIndex=%d", compareIndex);
                PreConditions.check(response.getCode() == DLedgerResponseCode.INCONSISTENT_STATE.getCode() || response.getCode() == DLedgerResponseCode.SUCCESS.getCode()
                    , DLedgerResponseCode.valueOf(response.getCode()), "compareIndex=%d", compareIndex);
                long truncateIndex = -1;

                if (response.getCode() == DLedgerResponseCode.SUCCESS.getCode()) {
                    //如果两者的日志序号相同，则无需截断，下次将直接先从节点发送 append 请求；否则将 truncateIndex 设置为响应结果中的 endIndex。
                    /*
                     * The comparison is successful:
                     * 1.Just change to append state, if the follower's end index is equal the compared index.
                     * 2.Truncate the follower, if the follower has some dirty entries.
                     */
                    if (compareIndex == response.getEndIndex()) {
                        changeState(compareIndex, PushEntryRequest.Type.APPEND);
                        break;
                    } else {
                        truncateIndex = compareIndex;
                    }
                } else if (response.getEndIndex() < dLedgerStore.getLedgerBeginIndex()
                    || response.getBeginIndex() > dLedgerStore.getLedgerEndIndex()) {
                    /*
                    如果从节点存储的最大日志序号小于主节点的最小序号，或者从节点的最小日志序号大于主节点的最大日志序号，
                    即两者不相交，这通常发生在从节点崩溃很长一段时间，而主节点删除了过期的条目时。truncateIndex
                    设置为主节点的 ledgerBeginIndex，即主节点目前最小的偏移量。
                     The follower's entries does not intersect with the leader.
                     This usually happened when the follower has crashed for a long time while the leader has deleted the expired entries.
                     Just truncate the follower.
                     */
                    truncateIndex = dLedgerStore.getLedgerBeginIndex();
                } else if (compareIndex < response.getBeginIndex()) {
                    /*
                    如果已比较的日志序号小于从节点的开始日志序号，很可能是从节点磁盘发送损耗，从主节点最小日志序号开始同步。
                     The compared index is smaller than the follower's begin index.
                     This happened rarely, usually means some disk damage.
                     Just truncate the follower.
                     */
                    truncateIndex = dLedgerStore.getLedgerBeginIndex();
                } else if (compareIndex > response.getEndIndex()) {
                    /*
                    如果已比较的日志序号大于从节点的最大日志序号，则已比较索引设置为从节点最大的日志序号，触发数据的继续同步。
                     The compared index is bigger than the follower's end index.
                     This happened frequently. For the compared index is usually starting from the end index of the leader.
                     */
                    compareIndex = response.getEndIndex();
                } else {
                    /*
                    如果已比较的日志序号大于从节点的开始日志序号，但小于从节点的最大日志序号，则待比较索引减一。
                      Compare failed and the compared index is in the range of follower's entries.
                     */
                    compareIndex--;
                }
                /*
                如果比较出来的日志序号小于主节点的最小日志需要，则设置为主节点的最小序号。
                 The compared index is smaller than the leader's begin index, truncate the follower.
                 */
                if (compareIndex < dLedgerStore.getLedgerBeginIndex()) {
                    truncateIndex = dLedgerStore.getLedgerBeginIndex();
                }
                /*
                如果比较出来的日志序号不等于 -1 ，则向从节点发送 TRUNCATE 请求。
                 If get value for truncateIndex, do it right now.
                 */
                if (truncateIndex != -1) {
                    changeState(truncateIndex, PushEntryRequest.Type.TRUNCATE);
                    doTruncate(truncateIndex);
                    break;
                }
            }
        }

        @Override
        public void doWork() {
            try {
                if (!checkAndFreshState()) {
                    waitForRunning(1);
                    return;
                }

                if (type.get() == PushEntryRequest.Type.APPEND) {
                    if (dLedgerConfig.isEnableBatchPush()) {
                        doBatchAppend();
                    } else {
                        doAppend();
                    }
                } else {
                    doCompare();
                }
                waitForRunning(1);
            } catch (Throwable t) {
                DLedgerEntryPusher.logger.error("[Push-{}]Error in {} writeIndex={} compareIndex={}", peerId, getName(), writeIndex, compareIndex, t);
                changeState(-1, PushEntryRequest.Type.COMPARE);
                DLedgerUtils.sleep(500);
            }
        }
    }

    /**
     * This thread will be activated by the follower.
     * Accept the push request and order it by the index, then append to ledger store one by one.
     *
     */
    private class EntryHandler extends ShutdownAbleThread {
        //上一次检查主服务器是否有 push 消息的时间戳。
        private long lastCheckFastForwardTimeMs = System.currentTimeMillis();
        //append 请求处理队列。
        ConcurrentMap<Long, Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>> writeRequestMap = new ConcurrentHashMap<>();
        //COMMIT、COMPARE、TRUNCATE 相关请求
        BlockingQueue<Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>> compareOrTruncateRequests = new ArrayBlockingQueue<Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>>>(100);

        public EntryHandler(Logger logger) {
            super("EntryHandler-" + memberState.getSelfId(), logger);
        }

        public CompletableFuture<PushEntryResponse> handlePush(PushEntryRequest request) throws Exception {
            //The timeout should smaller than the remoting layer's request timeout
            CompletableFuture<PushEntryResponse> future = new TimeoutFuture<>(1000);
            switch (request.getType()) {
                /**
                 * 如果是 APPEND 请求，放入到 writeRequestMap 集合中，如果已存在该数据结构，
                 * 说明主节点重复推送，构建返回结果，其状态码为 REPEATED_PUSH。放入到 writeRequestMap 中，由 doWork 方法定时去处理待写入的请求。
                 */
                case APPEND:
                    if (request.isBatch()) {
                        PreConditions.check(request.getBatchEntry() != null && request.getCount() > 0, DLedgerResponseCode.UNEXPECTED_ARGUMENT);
                    } else {
                        PreConditions.check(request.getEntry() != null, DLedgerResponseCode.UNEXPECTED_ARGUMENT);
                    }
                    long index = request.getFirstEntryIndex();
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> old = writeRequestMap.putIfAbsent(index, new Pair<>(request, future));
                    if (old != null) {
                        logger.warn("[MONITOR]The index {} has already existed with {} and curr is {}", index, old.getKey().baseInfo(), request.baseInfo());
                        future.complete(buildResponse(request, DLedgerResponseCode.REPEATED_PUSH.getCode()));
                    }
                    break;
                case COMMIT://如果是提交请求， 将请求存入 compareOrTruncateRequests 请求处理中，由 doWork 方法异步处理。
                    compareOrTruncateRequests.put(new Pair<>(request, future));
                    break;
                /**
                 * 如果是 COMPARE 或 TRUNCATE 请求，将待写入队列 writeRequestMap 清空，并将请求放入 compareOrTruncateRequests 请求队列中，由 doWork 方法异步处理。
                  */
                case COMPARE:
                case TRUNCATE:
                    PreConditions.check(request.getEntry() != null, DLedgerResponseCode.UNEXPECTED_ARGUMENT);
                    writeRequestMap.clear();
                    compareOrTruncateRequests.put(new Pair<>(request, future));
                    break;
                default:
                    logger.error("[BUG]Unknown type {} from {}", request.getType(), request.baseInfo());
                    future.complete(buildResponse(request, DLedgerResponseCode.UNEXPECTED_ARGUMENT.getCode()));
                    break;
            }
            wakeup();
            return future;
        }

        private PushEntryResponse buildResponse(PushEntryRequest request, int code) {
            PushEntryResponse response = new PushEntryResponse();
            response.setGroup(request.getGroup());
            response.setCode(code);
            response.setTerm(request.getTerm());
            if (request.getType() != PushEntryRequest.Type.COMMIT) {
                response.setIndex(request.getFirstEntryIndex());
                response.setCount(request.getCount());
            }
            response.setBeginIndex(dLedgerStore.getLedgerBeginIndex());
            response.setEndIndex(dLedgerStore.getLedgerEndIndex());
            return response;
        }

        private void handleDoAppend(long writeIndex, PushEntryRequest request,
            CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(writeIndex == request.getEntry().getIndex(), DLedgerResponseCode.INCONSISTENT_STATE);
                DLedgerEntry entry = dLedgerStore.appendAsFollower(request.getEntry(), request.getTerm(), request.getLeaderId());
                PreConditions.check(entry.getIndex() == writeIndex, DLedgerResponseCode.INCONSISTENT_STATE);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
                updateCommittedIndex(request.getTerm(), request.getCommitIndex());
            } catch (Throwable t) {
                logger.error("[HandleDoWrite] writeIndex={}", writeIndex, t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }
        }

        private CompletableFuture<PushEntryResponse> handleDoCompare(long compareIndex, PushEntryRequest request,
            CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(compareIndex == request.getEntry().getIndex(), DLedgerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.COMPARE, DLedgerResponseCode.UNKNOWN);
                DLedgerEntry local = dLedgerStore.get(compareIndex);
                PreConditions.check(request.getEntry().equals(local), DLedgerResponseCode.INCONSISTENT_STATE);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
            } catch (Throwable t) {
                logger.error("[HandleDoCompare] compareIndex={}", compareIndex, t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }
            return future;
        }

        /**
         * 调用 DLedgerStore 的 updateCommittedIndex 更新其已提交偏移量
         * @param committedIndex
         * @param request
         * @param future
         * @return
         */
        private CompletableFuture<PushEntryResponse> handleDoCommit(long committedIndex, PushEntryRequest request,
            CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(committedIndex == request.getCommitIndex(), DLedgerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.COMMIT, DLedgerResponseCode.UNKNOWN);
                updateCommittedIndex(request.getTerm(), committedIndex);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
            } catch (Throwable t) {
                logger.error("[HandleDoCommit] committedIndex={}", request.getCommitIndex(), t);
                future.complete(buildResponse(request, DLedgerResponseCode.UNKNOWN.getCode()));
            }
            return future;
        }

        /**
         * 删除从节点上 truncateIndex 日志序号之后的所有日志
         * @param truncateIndex
         * @param request
         * @param future
         * @return
         */
        private CompletableFuture<PushEntryResponse> handleDoTruncate(long truncateIndex, PushEntryRequest request,
            CompletableFuture<PushEntryResponse> future) {
            try {
                logger.info("[HandleDoTruncate] truncateIndex={} pos={}", truncateIndex, request.getEntry().getPos());
                PreConditions.check(truncateIndex == request.getEntry().getIndex(), DLedgerResponseCode.UNKNOWN);
                PreConditions.check(request.getType() == PushEntryRequest.Type.TRUNCATE, DLedgerResponseCode.UNKNOWN);
                long index = dLedgerStore.truncate(request.getEntry(), request.getTerm(), request.getLeaderId());
                PreConditions.check(index == truncateIndex, DLedgerResponseCode.INCONSISTENT_STATE);
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
                updateCommittedIndex(request.getTerm(), request.getCommitIndex());
            } catch (Throwable t) {
                logger.error("[HandleDoTruncate] truncateIndex={}", truncateIndex, t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }
            return future;
        }

        private void handleDoBatchAppend(long writeIndex, PushEntryRequest request,
            CompletableFuture<PushEntryResponse> future) {
            try {
                PreConditions.check(writeIndex == request.getFirstEntryIndex(), DLedgerResponseCode.INCONSISTENT_STATE);
                for (DLedgerEntry entry : request.getBatchEntry()) {
                    dLedgerStore.appendAsFollower(entry, request.getTerm(), request.getLeaderId());
                }
                future.complete(buildResponse(request, DLedgerResponseCode.SUCCESS.getCode()));
                updateCommittedIndex(request.getTerm(), request.getCommitIndex());
            } catch (Throwable t) {
                logger.error("[HandleDoBatchAppend]", t);
                future.complete(buildResponse(request, DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
            }

        }

        private void checkAppendFuture(long endIndex) {
            long minFastForwardIndex = Long.MAX_VALUE;
            for (Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair : writeRequestMap.values()) {
                long firstEntryIndex = pair.getKey().getFirstEntryIndex();
                long lastEntryIndex = pair.getKey().getLastEntryIndex();
                //Fall behind
                if (lastEntryIndex <= endIndex) {
                    try {
                        if (pair.getKey().isBatch()) {
                            for (DLedgerEntry dLedgerEntry : pair.getKey().getBatchEntry()) {
                                PreConditions.check(dLedgerEntry.equals(dLedgerStore.get(dLedgerEntry.getIndex())), DLedgerResponseCode.INCONSISTENT_STATE);
                            }
                        } else {
                            DLedgerEntry dLedgerEntry = pair.getKey().getEntry();
                            PreConditions.check(dLedgerEntry.equals(dLedgerStore.get(dLedgerEntry.getIndex())), DLedgerResponseCode.INCONSISTENT_STATE);
                        }
                        pair.getValue().complete(buildResponse(pair.getKey(), DLedgerResponseCode.SUCCESS.getCode()));
                        logger.warn("[PushFallBehind]The leader pushed an batch append entry last index={} smaller than current ledgerEndIndex={}, maybe the last ack is missed", lastEntryIndex, endIndex);
                    } catch (Throwable t) {
                        logger.error("[PushFallBehind]The leader pushed an batch append entry last index={} smaller than current ledgerEndIndex={}, maybe the last ack is missed", lastEntryIndex, endIndex, t);
                        pair.getValue().complete(buildResponse(pair.getKey(), DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
                    }
                    writeRequestMap.remove(pair.getKey().getFirstEntryIndex());
                    continue;
                }
                if (firstEntryIndex == endIndex + 1) {
                    return;
                }
                TimeoutFuture<PushEntryResponse> future = (TimeoutFuture<PushEntryResponse>) pair.getValue();
                if (!future.isTimeOut()) {
                    continue;
                }
                if (firstEntryIndex < minFastForwardIndex) {
                    minFastForwardIndex = firstEntryIndex;
                }
            }
            if (minFastForwardIndex == Long.MAX_VALUE) {
                return;
            }
            Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair = writeRequestMap.get(minFastForwardIndex);
            if (pair == null) {
                return;
            }
            logger.warn("[PushFastForward] ledgerEndIndex={} entryIndex={}", endIndex, minFastForwardIndex);
            pair.getValue().complete(buildResponse(pair.getKey(), DLedgerResponseCode.INCONSISTENT_STATE.getCode()));
        }
        /**
         * The leader does push entries to follower, and record the pushed index. But in the following conditions, the push may get stopped.
         *   * If the follower is abnormally shutdown, its ledger end index may be smaller than before. At this time, the leader may push fast-forward entries, and retry all the time.
         *   * If the last ack is missed, and no new message is coming in.The leader may retry push the last message, but the follower will ignore it.
         * @param endIndex
         */
        private void checkAbnormalFuture(long endIndex) {
            if (DLedgerUtils.elapsed(lastCheckFastForwardTimeMs) < 1000) {
                return;
            }
            lastCheckFastForwardTimeMs  = System.currentTimeMillis();
            if (writeRequestMap.isEmpty()) {
                return;
            }

            checkAppendFuture(endIndex);
        }

        @Override
        public void doWork() {
            try {
                if (!memberState.isFollower()) {//如果当前节点的状态不是从节点，则跳出。
                    waitForRunning(1);
                    return;
                }
                /**
                 * 如果 compareOrTruncateRequests 队列不为空，说明有COMMIT、COMPARE、TRUNCATE 等请求，
                 * 这类请求优先处理。
                 */
                if (compareOrTruncateRequests.peek() != null) {
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair = compareOrTruncateRequests.poll();
                    PreConditions.check(pair != null, DLedgerResponseCode.UNKNOWN);
                    switch (pair.getKey().getType()) {
                        case TRUNCATE:
                            handleDoTruncate(pair.getKey().getEntry().getIndex(), pair.getKey(), pair.getValue());
                            break;
                        case COMPARE:
                            handleDoCompare(pair.getKey().getEntry().getIndex(), pair.getKey(), pair.getValue());
                            break;
                        case COMMIT:
                            handleDoCommit(pair.getKey().getCommitIndex(), pair.getKey(), pair.getValue());
                            break;
                        default:
                            break;
                    }
                } else {
                    /**
                     * 如果只有 append 类请求，则根据当前节点最大的消息序号，尝试从 writeRequestMap 容器中，
                     * 获取下一个消息复制请求(ledgerEndIndex + 1) 为 key 去查找。如果不为空，
                     * 则执行 doAppend 请求，如果为空，则调用 checkAbnormalFuture 来处理异常情况。
                     */
                    long nextIndex = dLedgerStore.getLedgerEndIndex() + 1;
                    Pair<PushEntryRequest, CompletableFuture<PushEntryResponse>> pair = writeRequestMap.remove(nextIndex);
                    if (pair == null) {
                        checkAbnormalFuture(dLedgerStore.getLedgerEndIndex());
                        waitForRunning(1);
                        return;
                    }
                    PushEntryRequest request = pair.getKey();
                    if (request.isBatch()) {
                        handleDoBatchAppend(nextIndex, request, pair.getValue());
                    } else {
                        handleDoAppend(nextIndex, request, pair.getValue());
                    }
                }
            } catch (Throwable t) {
                DLedgerEntryPusher.logger.error("Error in {}", getName(), t);
                DLedgerUtils.sleep(100);
            }
        }
    }
}
