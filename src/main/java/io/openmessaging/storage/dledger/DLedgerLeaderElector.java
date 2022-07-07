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
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.protocol.HeartBeatRequest;
import io.openmessaging.storage.dledger.protocol.HeartBeatResponse;
import io.openmessaging.storage.dledger.protocol.LeadershipTransferRequest;
import io.openmessaging.storage.dledger.protocol.LeadershipTransferResponse;
import io.openmessaging.storage.dledger.protocol.VoteRequest;
import io.openmessaging.storage.dledger.protocol.VoteResponse;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leader选举实现器。
 */
public class DLedgerLeaderElector {

    private static Logger logger = LoggerFactory.getLogger(DLedgerLeaderElector.class);
    /**
     * 随机数生成器，对应raft协议中选举超时时间是一随机数。
     */
    private Random random = new Random();
    private DLedgerConfig dLedgerConfig;
    /**
     * 节点状态机。
     */
    private final MemberState memberState;
    /**
     * rpc服务，实现向集群内的节点发送心跳包、投票的RPC实现。
     */
    private DLedgerRpcService dLedgerRpcService;

    //as a server handler
    //record the last leader state
    /**
     * 上次收到心跳包的时间戳。
     */
    private volatile long lastLeaderHeartBeatTime = -1;
    /**
     * 上次发送心跳包的时间戳。
     */
    private volatile long lastSendHeartBeatTime = -1;
    /**
     * 上次成功收到心跳包的时间戳。
     */
    private volatile long lastSuccHeartBeatTime = -1;
    /**
     * 一个心跳包的周期，默认为2s。
     */
    private int heartBeatTimeIntervalMs = 2000;
    /**
     * 允许最大的N个心跳周期内未收到心跳包，状态为Follower的节点只有超过
     * maxHeartBeatLeak * heartBeatTimeIntervalMs
     * 的时间内未收到主节点的心跳包，才会重新进入 Candidate 状态，重新下一轮的选举。
     */
    private int maxHeartBeatLeak = 3;
    //as a client 发送下一个心跳包的时间戳。
    private long nextTimeToRequestVote = -1;
    /**
     * 是否应该立即发起投票。
     */
    private volatile boolean needIncreaseTermImmediately = false;
    /**
     * 最小的发送投票间隔时间，默认为300ms。
     */
    private int minVoteIntervalMs = 300;
    /**
     * 最大的发送投票的间隔，默认为1000ms。
     */
    private int maxVoteIntervalMs = 1000;
    /**
     * 注册的节点状态处理器，通过 addRoleChangeHandler 方法添加。
     */
    private List<RoleChangeHandler> roleChangeHandlers = new ArrayList<>();

    private VoteResponse.ParseResult lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
    /**
     * 上一次投票的开销。
     */
    private long lastVoteCost = 0L;
    /**
     * 状态机管理器。
     */
    private StateMaintainer stateMaintainer = new StateMaintainer("StateMaintainer", logger);

    private final TakeLeadershipTask takeLeadershipTask = new TakeLeadershipTask();

    public DLedgerLeaderElector(DLedgerConfig dLedgerConfig, MemberState memberState,
        DLedgerRpcService dLedgerRpcService) {
        this.dLedgerConfig = dLedgerConfig;
        this.memberState = memberState;
        this.dLedgerRpcService = dLedgerRpcService;
        refreshIntervals(dLedgerConfig);
    }

    public void startup() {
        stateMaintainer.start();//启动状态维护管理器。
        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            roleChangeHandler.startup();//遍历状态改变监听器并启动它，
        }
    }

    public void shutdown() {
        stateMaintainer.shutdown();
        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            roleChangeHandler.shutdown();
        }
    }

    private void refreshIntervals(DLedgerConfig dLedgerConfig) {
        this.heartBeatTimeIntervalMs = dLedgerConfig.getHeartBeatTimeIntervalMs();
        this.maxHeartBeatLeak = dLedgerConfig.getMaxHeartBeatLeak();
        this.minVoteIntervalMs = dLedgerConfig.getMinVoteIntervalMs();
        this.maxVoteIntervalMs = dLedgerConfig.getMaxVoteIntervalMs();
    }

    public CompletableFuture<HeartBeatResponse> handleHeartBeat(HeartBeatRequest request) throws Exception {

        if (!memberState.isPeerMember(request.getLeaderId())) {
            logger.warn("[BUG] [HandleHeartBeat] remoteId={} is an unknown member", request.getLeaderId());
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.UNKNOWN_MEMBER.getCode()));
        }

        if (memberState.getSelfId().equals(request.getLeaderId())) {
            logger.warn("[BUG] [HandleHeartBeat] selfId={} but remoteId={}", memberState.getSelfId(), request.getLeaderId());
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.UNEXPECTED_MEMBER.getCode()));
        }
        /**
         * 如果主节点的 term 小于 从节点的term，发送反馈给主节点，告知主节点的 term 已过时；如果投票轮次相同，并且发送心跳包的节点是该节点的主节点，则返回成功。
         */
        if (request.getTerm() < memberState.currTerm()) {
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
        } else if (request.getTerm() == memberState.currTerm()) {
            if (request.getLeaderId().equals(memberState.getLeaderId())) {
                lastLeaderHeartBeatTime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(new HeartBeatResponse());
            }
        }

        //abnormal case
        //hold the lock to get the latest term and leaderId
        synchronized (memberState) {
            if (request.getTerm() < memberState.currTerm()) {//如果主节的投票轮次小于当前投票轮次，则返回主节点投票轮次过期。
                return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
            } else if (request.getTerm() == memberState.currTerm()) {//如果投票轮次相同。
                if (memberState.getLeaderId() == null) {
                    //如果当前节点的主节点字段为空，则使用主节点的ID，并返回成功。
                    changeRoleToFollower(request.getTerm(), request.getLeaderId());
                    return CompletableFuture.completedFuture(new HeartBeatResponse());
                } else if (request.getLeaderId().equals(memberState.getLeaderId())) {
                    //如果当前节点的主节点就是发送心跳包的节点，则更新上一次收到心跳包的时间戳，并返回成功。
                    lastLeaderHeartBeatTime = System.currentTimeMillis();
                    return CompletableFuture.completedFuture(new HeartBeatResponse());
                } else {
                    //如果从节点的主节点与发送心跳包的节点ID不同，说明有另外一个Leaer，按道理来说是不会发送的，如果发生，则返回已存在- 主节点，标记该心跳包处理结束。
                    //this should not happen, but if happened
                    logger.error("[{}][BUG] currTerm {} has leader {}, but received leader {}", memberState.getSelfId(), memberState.currTerm(), memberState.getLeaderId(), request.getLeaderId());
                    return CompletableFuture.completedFuture(new HeartBeatResponse().code(DLedgerResponseCode.INCONSISTENT_LEADER.getCode()));
                }
            } else {
                //如果主节点的投票轮次大于从节点的投票轮次，则认为从节点并为准备好，则从节点进入Candidate 状态，并立即发起一次投票。
                //To make it simple, for larger term, do not change to follower immediately
                //first change to candidate, and notify the state-maintainer thread
                changeRoleToCandidate(request.getTerm());
                needIncreaseTermImmediately = true;
                //TOOD notify
                return CompletableFuture.completedFuture(new HeartBeatResponse().code(DLedgerResponseCode.TERM_NOT_READY.getCode()));
            }
        }
    }

    /**
     * 设置为leader
     * @param term
     */
    public void changeRoleToLeader(long term) {
        synchronized (memberState) {
            if (memberState.currTerm() == term) {
                memberState.changeToLeader(term);
                lastSendHeartBeatTime = -1;
                handleRoleChange(term, MemberState.Role.LEADER);
                logger.info("[{}] [ChangeRoleToLeader] from term: {} and currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            } else {
                logger.warn("[{}] skip to be the leader in term: {}, but currTerm is: {}", memberState.getSelfId(), term, memberState.currTerm());
            }
        }
    }

    /**
     * 将角色更改为候选人
     * @param term
     */
    public void changeRoleToCandidate(long term) {
        synchronized (memberState) {
            if (term >= memberState.currTerm()) {
                memberState.changeToCandidate(term);
                handleRoleChange(term, MemberState.Role.CANDIDATE);
                logger.info("[{}] [ChangeRoleToCandidate] from term: {} and currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            } else {
                logger.info("[{}] skip to be candidate in term: {}, but currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            }
        }
    }

    //just for test
    public void testRevote(long term) {
        changeRoleToCandidate(term);
        lastParseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
        nextTimeToRequestVote = -1;
    }

    public void changeRoleToFollower(long term, String leaderId) {
        logger.info("[{}][ChangeRoleToFollower] from term: {} leaderId: {} and currTerm: {}", memberState.getSelfId(), term, leaderId, memberState.currTerm());
        lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
        memberState.changeToFollower(term, leaderId);
        lastLeaderHeartBeatTime = System.currentTimeMillis();
        handleRoleChange(term, MemberState.Role.FOLLOWER);
    }

    public CompletableFuture<VoteResponse> handleVote(VoteRequest request, boolean self) {
        //hold the lock to get the latest term, leaderId, ledgerEndIndex
        synchronized (memberState) {
            if (!memberState.isPeerMember(request.getLeaderId())) {
                logger.warn("[BUG] [HandleVote] remoteId={} is an unknown member", request.getLeaderId());
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_UNKNOWN_LEADER));
            }
            if (!self && memberState.getSelfId().equals(request.getLeaderId())) {
                logger.warn("[BUG] [HandleVote] selfId={} but remoteId={}", memberState.getSelfId(), request.getLeaderId());
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_UNEXPECTED_LEADER));
            }
            //如果请求节点的 ledgerEndTerm 小于当前节点的 ledgerEndTerm 则拒绝，其原因是请求节点的日志复制进度比当前节点低，这种情况是不能成为主节点的。
            if (request.getLedgerEndTerm() < memberState.getLedgerEndTerm()) {
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_EXPIRED_LEDGER_TERM));
            } else if (request.getLedgerEndTerm() == memberState.getLedgerEndTerm() && request.getLedgerEndIndex() < memberState.getLedgerEndIndex()) {
                //如果 ledgerEndTerm 相等，但是 ledgerEndIndex 比当前节点小，则拒绝，原因请求节点的日志复制进度比当前节点低，这种情况是不能成为主节点的。
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_SMALL_LEDGER_END_INDEX));
            }
            //拒绝票，如果自己维护的term小于远端维护的term
            if (request.getTerm() < memberState.currTerm()) {
                //此种情况下投拒绝票，也就是说在 raft 协议的世界中，谁的 term 越大，越有话语权。
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_EXPIRED_VOTE_TERM));
            } else if (request.getTerm() == memberState.currTerm()) {//如果发起投票节点的 term 等于当前节点的 term
                //如果两者的 term 相等，说明两者都处在同一个投票轮次中，地位平等，接下来看该节点是否已经投过票。
                if (memberState.currVoteFor() == null) {//如果未投票
                    //let it go
                } else if (memberState.currVoteFor().equals(request.getLeaderId())) {//已投票给请求节点
                    //repeat just let it go
                } else {
                    if (memberState.getLeaderId() != null) {//如果该节点还未有Leader节点，但已经投了其他节点的票，则拒绝请求节点，并告知已投票。
                        //拒绝票，原因是因为集群中已经存在Leaer了
                        return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_ALREADY_HAS_LEADER));
                    } else {
                        //拒绝已投票，原因是已经投了其他节点的票。
                        return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_ALREADY_VOTED));
                    }
                }
            } else {
                //拒绝请求节点的投票请求，并告知自身还未准备投票，自身会使用请求节点的投票轮次立即进入到Candidate状态。
                //stepped down by larger term
                changeRoleToCandidate(request.getTerm());
                needIncreaseTermImmediately = true;
                //only can handleVote when the term is consistent
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_TERM_NOT_READY));
            }
            //如果请求的 term 小于 ledgerEndTerm
            if (request.getTerm() < memberState.getLedgerEndTerm()) {

                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.getLedgerEndTerm()).voteResult(VoteResponse.RESULT.REJECT_TERM_SMALL_THAN_LEDGER));
            }
            //接受新领导
            if (!self && isTakingLeadership() && request.getLedgerEndTerm() == memberState.getLedgerEndTerm() && memberState.getLedgerEndIndex() >= request.getLedgerEndIndex()) {
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_TAKING_LEADERSHIP));
            }
            //赞成票
            memberState.setCurrVoteFor(request.getLeaderId());
            return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.ACCEPT));
        }
    }

    /**
     * 遍历集群中的节点，异步发送心跳包。
     * @param term
     * @param leaderId
     * @throws Exception
     */
    private void sendHeartbeats(long term, String leaderId) throws Exception {
        final AtomicInteger allNum = new AtomicInteger(1);
        final AtomicInteger succNum = new AtomicInteger(1);
        final AtomicInteger notReadyNum = new AtomicInteger(0);
        final AtomicLong maxTerm = new AtomicLong(-1);
        final AtomicBoolean inconsistLeader = new AtomicBoolean(false);
        final CountDownLatch beatLatch = new CountDownLatch(1);
        long startHeartbeatTimeMs = System.currentTimeMillis();
        for (String id : memberState.getPeerMap().keySet()) {
            if (memberState.getSelfId().equals(id)) {
                continue;
            }
            HeartBeatRequest heartBeatRequest = new HeartBeatRequest();
            heartBeatRequest.setGroup(memberState.getGroup());
            heartBeatRequest.setLocalId(memberState.getSelfId());
            heartBeatRequest.setRemoteId(id);
            heartBeatRequest.setLeaderId(leaderId);
            heartBeatRequest.setTerm(term);
            CompletableFuture<HeartBeatResponse> future = dLedgerRpcService.heartBeat(heartBeatRequest);
            future.whenComplete((HeartBeatResponse x, Throwable ex) -> {
                try {
                    if (ex != null) {
                        memberState.getPeersLiveTable().put(id, Boolean.FALSE);
                        throw ex;
                    }
                    switch (DLedgerResponseCode.valueOf(x.getCode())) {
                        case SUCCESS://心跳包成功响应。
                            succNum.incrementAndGet();
                            break;
                        case EXPIRED_TERM://主节点的投票 term 小于从节点的投票轮次。
                            maxTerm.set(x.getTerm());
                            break;
                        case INCONSISTENT_LEADER://从节点已经有了新的主节点。
                            inconsistLeader.compareAndSet(false, true);
                            break;
                        case TERM_NOT_READY://从节点未准备好。
                            notReadyNum.incrementAndGet();
                            break;
                        default:
                            break;
                    }

                    if (x.getCode() == DLedgerResponseCode.NETWORK_ERROR.getCode())
                        memberState.getPeersLiveTable().put(id, Boolean.FALSE);
                    else
                        memberState.getPeersLiveTable().put(id, Boolean.TRUE);

                    if (memberState.isQuorum(succNum.get())
                        || memberState.isQuorum(succNum.get() + notReadyNum.get())) {
                        beatLatch.countDown();
                    }
                } catch (Throwable t) {
                    logger.error("heartbeat response failed", t);
                } finally {
                    allNum.incrementAndGet();
                    if (allNum.get() == memberState.peerSize()) {
                        beatLatch.countDown();
                    }
                }
            });
        }
        beatLatch.await(heartBeatTimeIntervalMs, TimeUnit.MILLISECONDS);
        if (memberState.isQuorum(succNum.get())) {//如果成功的票数大于进群内的半数，则表示集群状态正常，正常按照心跳包间隔发送心跳包
            lastSuccHeartBeatTime = System.currentTimeMillis();
        } else {
            logger.info("[{}] Parse heartbeat responses in cost={} term={} allNum={} succNum={} notReadyNum={} inconsistLeader={} maxTerm={} peerSize={} lastSuccHeartBeatTime={}",
                memberState.getSelfId(), DLedgerUtils.elapsed(startHeartbeatTimeMs), term, allNum.get(), succNum.get(), notReadyNum.get(), inconsistLeader.get(), maxTerm.get(), memberState.peerSize(), new Timestamp(lastSuccHeartBeatTime));
            if (memberState.isQuorum(succNum.get() + notReadyNum.get())) {//如果成功的票数加上未准备的投票的节点数量超过集群内的半数，则立即发送心跳包
                lastSendHeartBeatTime = -1;
            } else if (maxTerm.get() > term) {//如果从节点的投票轮次比主节点的大，则使用从节点的投票轮次，或从节点已经有了另外的主节点，节点状态从 Leader 转换为 Candidate。
                changeRoleToCandidate(maxTerm.get());
            } else if (inconsistLeader.get()) {
                changeRoleToCandidate(term);
            } else if (DLedgerUtils.elapsed(lastSuccHeartBeatTime) > maxHeartBeatLeak * heartBeatTimeIntervalMs) {
                changeRoleToCandidate(term);
            }
        }
    }

    /**
     * 被其他节点选举成为领导后，会执行该方法，其他节点的状态还是Candidate，并在计时器过期后，又尝试去发起选举
     * @throws Exception
     */
    private void maintainAsLeader() throws Exception {
        //首先判断上一次发送心跳的时间与当前时间的差值是否大于心跳包发送间隔，如果超过，则说明需要发送心跳包。
        if (DLedgerUtils.elapsed(lastSendHeartBeatTime) > heartBeatTimeIntervalMs) {
            long term;
            String leaderId;
            synchronized (memberState) {
                if (!memberState.isLeader()) {//如果当前不是leader节点，则直接返回
                    //stop sending
                    return;
                }
                term = memberState.currTerm();
                leaderId = memberState.getLeaderId();
                //重置心跳包发送计时器。
                lastSendHeartBeatTime = System.currentTimeMillis();
            }
            //向集群内的所有节点发送心跳包，稍后会详细介绍心跳包的发送。
            sendHeartbeats(term, leaderId);
        }
    }

    /**
     * 当 Candidate 状态的节点在收到主节点发送的心跳包后，会将状态变更为follower
     */
    private void maintainAsFollower() {
        if (DLedgerUtils.elapsed(lastLeaderHeartBeatTime) > 2 * heartBeatTimeIntervalMs) {
            logger.debug("Follower 心跳包超时，变为Candidate，lastLeaderHeartBeatTime:{},2倍heartBeatTimeIntervalMs:{}",lastLeaderHeartBeatTime,2 * heartBeatTimeIntervalMs);
            synchronized (memberState) {
                if (memberState.isFollower() && (DLedgerUtils.elapsed(lastLeaderHeartBeatTime) > maxHeartBeatLeak * heartBeatTimeIntervalMs)) {
                    logger.info("[{}][HeartBeatTimeOut] lastLeaderHeartBeatTime: {} heartBeatTimeIntervalMs: {} lastLeader={}", memberState.getSelfId(), new Timestamp(lastLeaderHeartBeatTime), heartBeatTimeIntervalMs, memberState.getLeaderId());
                    changeRoleToCandidate(memberState.currTerm());
                }
            }
        }
    }

    /**
     * 向集群内的其他节点发起投票请，并返回投票结果列表，
     * @param term 发起投票的节点当前的投票轮次。
     * @param ledgerEndTerm 发起投票节点维护的已知的最大投票轮次。
     * @param ledgerEndIndex 发起投票节点维护的已知的最大日志条目索引。
     * @return
     * @throws Exception
     */
    private List<CompletableFuture<VoteResponse>> voteForQuorumResponses(long term, long ledgerEndTerm,
        long ledgerEndIndex) throws Exception {
        List<CompletableFuture<VoteResponse>> responses = new ArrayList<>();
        for (String id : memberState.getPeerMap().keySet()) {//遍历集群内的节点集合，准备异步发起投票请求。这个集合在启动的时候指定，不能修改。
            VoteRequest voteRequest = new VoteRequest();//构建投票请求。
            voteRequest.setGroup(memberState.getGroup());
            voteRequest.setLedgerEndIndex(ledgerEndIndex);
            voteRequest.setLedgerEndTerm(ledgerEndTerm);
            voteRequest.setLeaderId(memberState.getSelfId());
            voteRequest.setTerm(term);
            voteRequest.setRemoteId(id);
            CompletableFuture<VoteResponse> voteResponse;
            //如果是发送给自己的，则直接调用handleVote进行投票请求响应，如果是发送给集群内的其他节点，则通过网络发送投票请求，对端节点调用各自的handleVote对集群进行响应。
            if (memberState.getSelfId().equals(id)) {
                logger.debug("[{}]投票请求发起者为本人,调用本地",memberState.getSelfId());
                voteResponse = handleVote(voteRequest, true);
            } else {
                //async
                voteResponse = dLedgerRpcService.vote(voteRequest);
            }
            responses.add(voteResponse);

        }
        return responses;
    }

    /**
     * 是否 正在取得领导地位
     * @return
     */
    private boolean isTakingLeadership() {
        if (dLedgerConfig.getPreferredLeaderIds() != null && memberState.getTermToTakeLeadership() == memberState.currTerm()) {
            List<String> preferredLeaderIds = Arrays.asList(dLedgerConfig.getPreferredLeaderIds().split(";"));
            return preferredLeaderIds.contains(memberState.getSelfId());
        }
        return false;
    }

    /**
     * 获得下一次投票时间
     * @return
     */
    private long getNextTimeToRequestVote() {
        if (isTakingLeadership()) {
            return System.currentTimeMillis() + dLedgerConfig.getMinTakeLeadershipVoteIntervalMs() +
                random.nextInt(dLedgerConfig.getMaxTakeLeadershipVoteIntervalMs() - dLedgerConfig.getMinTakeLeadershipVoteIntervalMs());
        }
        //当前时间戳 +   最小投票间隔(300ms) + （1000- 300 ）之间的随机值。
        return System.currentTimeMillis() + minVoteIntervalMs + random.nextInt(maxVoteIntervalMs - minVoteIntervalMs);
    }

    private void maintainAsCandidate() throws Exception {
        //for candidate
        if (System.currentTimeMillis() < nextTimeToRequestVote && !needIncreaseTermImmediately) {
            return;
        }
        long term;// 投票轮次。
        long ledgerEndTerm;//Leader节点当前的投票轮次。
        long ledgerEndIndex;//当前日志的最大序列，即下一条日志的开始index
        synchronized (memberState) {
            if (!memberState.isCandidate()) {//如果不是候选人
                return;
            }
            /**
             * 如果等待下次投票 或 立即发起投票
             */
            if (lastParseResult == VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT || needIncreaseTermImmediately) {
                long prevTerm = memberState.currTerm();
                term = memberState.nextTerm();//投票轮次。+1
                logger.info("{}_[INCREASE_TERM] from {} to {}", memberState.getSelfId(), prevTerm, term);
                lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;//节点状态变为重新投票
            } else {
                term = memberState.currTerm();//获取投票轮次
            }
            ledgerEndIndex = memberState.getLedgerEndIndex();
            ledgerEndTerm = memberState.getLedgerEndTerm();
        }
        if (needIncreaseTermImmediately) {//立即发起投票 重置标记位为false，并重新设置下一次投票超时时间
            nextTimeToRequestVote = getNextTimeToRequestVote();
            needIncreaseTermImmediately = false;
            return;
        }

        long startVoteTimeMs = System.currentTimeMillis();
        final List<CompletableFuture<VoteResponse>> quorumVoteResponses = voteForQuorumResponses(term, ledgerEndTerm, ledgerEndIndex);
        final AtomicLong knownMaxTermInGroup = new AtomicLong(term);//已知的最大投票轮次。
        final AtomicInteger allNum = new AtomicInteger(0);//所有投票票数。
        final AtomicInteger validNum = new AtomicInteger(0);//有效投票数。
        final AtomicInteger acceptedNum = new AtomicInteger(0);//获得的投票数。
        //未准备投票的节点数量，如果对端节点的投票轮次小于发起投票的轮次，则认为对端未准备好，对端节点使用本次的轮次进入 - Candidate 状态。
        final AtomicInteger notReadyTermNum = new AtomicInteger(0);
        final AtomicInteger biggerLedgerNum = new AtomicInteger(0);//发起投票的节点的ledgerEndTerm小于对端节点的个数。
        final AtomicBoolean alreadyHasLeader = new AtomicBoolean(false);//是否已经存在Leader。

        CountDownLatch voteLatch = new CountDownLatch(1);
        for (CompletableFuture<VoteResponse> future : quorumVoteResponses) {
            future.whenComplete((VoteResponse x, Throwable ex) -> {
                try {
                    if (ex != null) {
                        throw ex;
                    }
                    logger.info("[{}][GetVoteResponse] {}", memberState.getSelfId(), JSON.toJSONString(x));
                    if (x.getVoteResult() != VoteResponse.RESULT.UNKNOWN) {
                        validNum.incrementAndGet();
                    }
                    synchronized (knownMaxTermInGroup) {
                        switch (x.getVoteResult()) {
                            case ACCEPT://赞成票，acceptedNum加一，只有得到的赞成票超过集群节点数量的一半才能成为Leader。
                                acceptedNum.incrementAndGet();
                                break;
                            case REJECT_ALREADY_VOTED://拒绝票，原因是已经投了其他节点的票。
                            case REJECT_TAKING_LEADERSHIP://拒绝接受领导
                                break;
                            //拒绝票，原因是因为集群中已经存在Leaer了。alreadyHasLeader设置为true，无需在判断其他投票结果了，结束本轮投票。
                            case REJECT_ALREADY_HAS_LEADER:
                                alreadyHasLeader.compareAndSet(false, true);
                                break;
                            //拒绝票，如果自己维护的term小于远端维护的ledgerEndTerm，则返回该结果，如果对端的team大于自己的team，需要记录对端最大的投票轮次，以便更新自己的投票轮次。
                            case REJECT_TERM_SMALL_THAN_LEDGER:
                            //拒绝票，如果自己维护的term小于远端维护的term，更新自己维护的投票轮次。
                            case REJECT_EXPIRED_VOTE_TERM:
                                if (x.getTerm() > knownMaxTermInGroup.get()) {
                                    knownMaxTermInGroup.set(x.getTerm());
                                }
                                break;
                            //拒绝票，如果自己维护的 ledgerTerm小于对端维护的ledgerTerm，则返回该结果。如果是此种情况，增加计数器- biggerLedgerNum的值。
                            case REJECT_EXPIRED_LEDGER_TERM:
                            //拒绝票，如果对端的ledgerTeam与自己维护的ledgerTeam相等，但是自己维护的dedgerEndIndex小于对端维护的值，返回该值，增加biggerLedgerNum计数器的值。
                            case REJECT_SMALL_LEDGER_END_INDEX:
                                biggerLedgerNum.incrementAndGet();
                                break;
                            //拒绝票，对端的投票轮次小于自己的team，则认为对端还未准备好投票，对端使用自己的投票轮次，是自己进入到Candidate状态。
                            case REJECT_TERM_NOT_READY:
                                notReadyTermNum.incrementAndGet();
                                break;
                            default:
                                break;

                        }
                    }
                    if (alreadyHasLeader.get()
                        || memberState.isQuorum(acceptedNum.get())
                        || memberState.isQuorum(acceptedNum.get() + notReadyTermNum.get())) {
                        voteLatch.countDown();
                    }
                } catch (Throwable t) {
                    logger.error("vote response failed", t);
                } finally {
                    allNum.incrementAndGet();
                    if (allNum.get() == memberState.peerSize()) {
                        voteLatch.countDown();
                    }
                }
            });

        }

        try {
            voteLatch.await(2000 + random.nextInt(maxVoteIntervalMs), TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {

        }

        lastVoteCost = DLedgerUtils.elapsed(startVoteTimeMs);
        VoteResponse.ParseResult parseResult;
        if (knownMaxTermInGroup.get() > term) {
            /**
             * 如果对端的投票轮次大于发起投票的节点，则该节点使用对端的轮次，重新进入到Candidate状态，并且重置投票计时器，其值为“1个常规计时器”
             */
            parseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
            nextTimeToRequestVote = getNextTimeToRequestVote();
            changeRoleToCandidate(knownMaxTermInGroup.get());
        } else if (alreadyHasLeader.get()) {
            /**
             * 已经存在Leader，该节点重新进入到Candidate,并重置定时器，该定时器的时间：
             * “1个常规计时器” + heartBeatTimeIntervalMs * maxHeartBeatLeak ，
             * 其中
             * heartBeatTimeIntervalMs 为一次心跳间隔时间，
             * maxHeartBeatLeak 为 允许最大丢失的心跳包，
             * 即如果Flower节点在多少个心跳周期内未收到心跳包，则认为Leader已下线。
             */
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote() + heartBeatTimeIntervalMs * maxHeartBeatLeak;
        } else if (!memberState.isQuorum(validNum.get())) {
            /**
             * 如果收到的有效票数未超过半数，则重置计时器为“ 1个常规计时器”，然后等待重新投票，注意状态为WAIT_TO_REVOTE，该状态下的特征是下次投票时不增加投票轮次。
             */
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote();
        } else if (!memberState.isQuorum(validNum.get() - biggerLedgerNum.get())) {
            // 如果有效的票数-发起投票的节点的ledgerEndTerm小于对端节点的个数 过半
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote() + maxVoteIntervalMs;
        } else if (memberState.isQuorum(acceptedNum.get())) {
            /**
             * 如果得到的赞同票超过半数，则成为Leader。
             */
            parseResult = VoteResponse.ParseResult.PASSED;
        } else if (memberState.isQuorum(acceptedNum.get() + notReadyTermNum.get())) {
            /**
             * 如果得到的赞成票加上未准备投票的节点数超过半数，则应该立即发起投票，故其结果为REVOTE_IMMEDIATELY。
             */
            parseResult = VoteResponse.ParseResult.REVOTE_IMMEDIATELY;
        } else {
            /**
             * 其他情况，开启下一轮投票。
             */
            parseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
            nextTimeToRequestVote = getNextTimeToRequestVote();
        }
        lastParseResult = parseResult;
        logger.info("[{}] [PARSE_VOTE_RESULT] cost={} term={} memberNum={} allNum={} acceptedNum={} notReadyTermNum={} biggerLedgerNum={} alreadyHasLeader={} maxTerm={} result={}",
            memberState.getSelfId(), lastVoteCost, term, memberState.peerSize(), allNum, acceptedNum, notReadyTermNum, biggerLedgerNum, alreadyHasLeader, knownMaxTermInGroup.get(), parseResult);

        if (parseResult == VoteResponse.ParseResult.PASSED) {
            logger.info("[{}] [VOTE_RESULT] has been elected to be the leader in term {}", memberState.getSelfId(), term);
            changeRoleToLeader(term);
        }
    }

    /**
     * The core method of maintainer. Run the specified logic according to the current role: candidate => propose a
     * vote. leader => send heartbeats to followers, and step down to candidate when quorum followers do not respond.
     * follower => accept heartbeats, and change to candidate when no heartbeat from leader.
     * 在raft协议中，节点的状态默认为follower，DLedger的实现从candidate开始，一开始，集群内的所有节点都会尝试发起投票，这样第一轮要达成选举几乎不太可能。
     *
     * @throws Exception
     */
    private void maintainState() throws Exception {
        if (memberState.isLeader()) {//领导者，主节点，该状态下，需要定时向从节点发送心跳包，用来传播数据、确保其领导地位。
            maintainAsLeader();
        } else if (memberState.isFollower()) {//从节点，该状态下，会开启定时器，尝试进入到candidate状态，以便发起投票选举，同时一旦收到主节点的心跳包，则重置定时器。
            maintainAsFollower();
        } else {
            maintainAsCandidate();//候选者，该状态下的节点会发起投票，尝试选择自己为主节点，选举成功后，不会存在该状态下的节点。
        }
    }

    private void handleRoleChange(long term, MemberState.Role role) {
        try {
            takeLeadershipTask.check(term, role);
        } catch (Throwable t) {
            logger.error("takeLeadershipTask.check failed. ter={}, role={}", term, role, t);
        }

        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            try {
                roleChangeHandler.handle(term, role);
            } catch (Throwable t) {
                logger.warn("Handle role change failed term={} role={} handler={}", term, role, roleChangeHandler.getClass(), t);
            }
        }
    }

    public void addRoleChangeHandler(RoleChangeHandler roleChangeHandler) {
        if (!roleChangeHandlers.contains(roleChangeHandler)) {
            roleChangeHandlers.add(roleChangeHandler);
        }
    }

    /**
     * 处理领导层调动
     * @param request
     * @return
     * @throws Exception
     */
    public CompletableFuture<LeadershipTransferResponse> handleLeadershipTransfer(
        LeadershipTransferRequest request) throws Exception {
        logger.info("handleLeadershipTransfer: {}", request);
        synchronized (memberState) {
            if (memberState.currTerm() != request.getTerm()) {
                logger.warn("[BUG] [HandleLeaderTransfer] currTerm={} != request.term={}", memberState.currTerm(), request.getTerm());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.INCONSISTENT_TERM.getCode()));
            }

            if (!memberState.isLeader()) {
                logger.warn("[BUG] [HandleLeaderTransfer] selfId={} is not leader", request.getLeaderId());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.NOT_LEADER.getCode()));
            }

            if (memberState.getTransferee() != null) {
                logger.warn("[BUG] [HandleLeaderTransfer] transferee={} is already set", memberState.getTransferee());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.LEADER_TRANSFERRING.getCode()));
            }

            memberState.setTransferee(request.getTransfereeId());
        }
        LeadershipTransferRequest takeLeadershipRequest = new LeadershipTransferRequest();
        takeLeadershipRequest.setGroup(memberState.getGroup());
        takeLeadershipRequest.setLeaderId(memberState.getLeaderId());
        takeLeadershipRequest.setLocalId(memberState.getSelfId());
        takeLeadershipRequest.setRemoteId(request.getTransfereeId());
        takeLeadershipRequest.setTerm(request.getTerm());
        takeLeadershipRequest.setTakeLeadershipLedgerIndex(memberState.getLedgerEndIndex());
        takeLeadershipRequest.setTransferId(memberState.getSelfId());
        takeLeadershipRequest.setTransfereeId(request.getTransfereeId());
        if (memberState.currTerm() != request.getTerm()) {
            logger.warn("[HandleLeaderTransfer] term changed, cur={} , request={}", memberState.currTerm(), request.getTerm());
            return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
        }

        return dLedgerRpcService.leadershipTransfer(takeLeadershipRequest).thenApply(response -> {
            synchronized (memberState) {
                if (response.getCode() != DLedgerResponseCode.SUCCESS.getCode() ||
                    (memberState.currTerm() == request.getTerm() && memberState.getTransferee() != null)) {
                    logger.warn("leadershipTransfer failed, set transferee to null");
                    memberState.setTransferee(null);
                }
            }
            return response;
        });
    }

    public CompletableFuture<LeadershipTransferResponse> handleTakeLeadership(
        LeadershipTransferRequest request) throws Exception {
        logger.debug("handleTakeLeadership.request={}", request);
        synchronized (memberState) {
            if (memberState.currTerm() != request.getTerm()) {
                logger.warn("[BUG] [handleTakeLeadership] currTerm={} != request.term={}", memberState.currTerm(), request.getTerm());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.INCONSISTENT_TERM.getCode()));
            }

            long targetTerm = request.getTerm() + 1;
            memberState.setTermToTakeLeadership(targetTerm);
            CompletableFuture<LeadershipTransferResponse> response = new CompletableFuture<>();
            takeLeadershipTask.update(request, response);
            changeRoleToCandidate(targetTerm);
            needIncreaseTermImmediately = true;
            return response;
        }
    }

    private class TakeLeadershipTask {
        private LeadershipTransferRequest request;
        private CompletableFuture<LeadershipTransferResponse> responseFuture;

        public synchronized void update(LeadershipTransferRequest request,
            CompletableFuture<LeadershipTransferResponse> responseFuture) {
            this.request = request;
            this.responseFuture = responseFuture;
        }

        public synchronized void check(long term, MemberState.Role role) {
            logger.trace("TakeLeadershipTask called, term={}, role={}", term, role);
            if (memberState.getTermToTakeLeadership() == -1 || responseFuture == null) {
                return;
            }
            LeadershipTransferResponse response = null;
            if (term > memberState.getTermToTakeLeadership()) {
                response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.EXPIRED_TERM.getCode());
            } else if (term == memberState.getTermToTakeLeadership()) {
                switch (role) {
                    case LEADER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.SUCCESS.getCode());
                        break;
                    case FOLLOWER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.TAKE_LEADERSHIP_FAILED.getCode());
                        break;
                    default:
                        return;
                }
            } else {
                switch (role) {
                    /*
                     * The node may receive heartbeat before term increase as a candidate,
                     * then it will be follower and term < TermToTakeLeadership
                     */
                    case FOLLOWER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.TAKE_LEADERSHIP_FAILED.getCode());
                        break;
                    default:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.INTERNAL_ERROR.getCode());
                }
            }

            responseFuture.complete(response);
            logger.info("TakeLeadershipTask finished. request={}, response={}, term={}, role={}", request, response, term, role);
            memberState.setTermToTakeLeadership(-1);
            responseFuture = null;
            request = null;
        }
    }

    public interface RoleChangeHandler {
        void handle(long term, MemberState.Role role);

        void startup();

        void shutdown();
    }

    public class StateMaintainer extends ShutdownAbleThread {

        public StateMaintainer(String name, Logger logger) {
            super(name, logger);
        }

        @Override
        public void doWork() {
            try {
                if (DLedgerLeaderElector.this.dLedgerConfig.isEnableLeaderElector()) {//如果该节点参与Leader选举，则首先调用
                    DLedgerLeaderElector.this.refreshIntervals(dLedgerConfig);//重置定时器，然后驱动状态机
                    DLedgerLeaderElector.this.maintainState();
                }
                sleep(10);//没执行一次选主，休息10ms。
            } catch (Throwable t) {
                DLedgerLeaderElector.logger.error("Error in heartbeat", t);
            }
        }

    }
}
