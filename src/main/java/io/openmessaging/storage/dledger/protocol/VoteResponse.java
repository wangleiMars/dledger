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

package io.openmessaging.storage.dledger.protocol;

import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.UNKNOWN;

public class VoteResponse extends RequestOrResponse {

    public RESULT voteResult = UNKNOWN;

    public VoteResponse() {

    }

    public VoteResponse(VoteRequest request) {
        copyBaseInfo(request);
    }

    public RESULT getVoteResult() {
        return voteResult;
    }

    public void setVoteResult(RESULT voteResult) {
        this.voteResult = voteResult;
    }

    public VoteResponse voteResult(RESULT voteResult) {
        this.voteResult = voteResult;
        return this;
    }

    public VoteResponse term(long term) {
        this.term = term;
        return this;
    }

    public enum RESULT {
        UNKNOWN,
        /**
         * 赞成票
         */
        ACCEPT,
        /**
         * 拒绝票，未知的leader
         */
        REJECT_UNKNOWN_LEADER,
        /**
         * 拒绝意外的领导
         */
        REJECT_UNEXPECTED_LEADER,
        /**
         * 拒绝票 过期的投票
         */
        REJECT_EXPIRED_VOTE_TERM,
        /**
         * 拒绝已投票，原因是已经投了其他节点的票。
         */
        REJECT_ALREADY_VOTED,
        /**
         * 拒绝票，原因是因为集群中已经存在Leaer了。alreadyHasLeader设置为true，无需在判断其他投票结果了，结束本轮投票。
         */
        REJECT_ALREADY_HAS_LEADER,
        /**
         * 自身还未准备好投票
         * 拒绝票，对端的投票轮次小于自己的team，则认为对端还未准备好投票，对端使用自己的投票轮次，是自己进入到Candidate状态。
         */
        REJECT_TERM_NOT_READY,
        /**
         * 拒绝票， 拒绝TERM小于ledger的期限
         */
        REJECT_TERM_SMALL_THAN_LEDGER,
        /**
         * 拒绝票，过期的ledger的TERM
         */
        REJECT_EXPIRED_LEDGER_TERM,
        /**
         * 拒绝票，过小的leader endIndex
         */
        REJECT_SMALL_LEDGER_END_INDEX,
        /**
         * 拒绝接受领导
         */
        REJECT_TAKING_LEADERSHIP;
    }

    public enum ParseResult {
        /**
         * 重新投票
         */
        WAIT_TO_REVOTE,
        /**
         * 立即撤销
         */
        REVOTE_IMMEDIATELY,
        /**
         * 通过
         */
        PASSED,
        /**
         * 等待下次投票
         */
        WAIT_TO_VOTE_NEXT;
    }
}
