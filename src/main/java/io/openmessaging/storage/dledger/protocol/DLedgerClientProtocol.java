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

import java.util.concurrent.CompletableFuture;

/**
 * Both the RaftLogServer(inbound) and RaftRpcService (outbound) should implement this protocol
 */
public interface DLedgerClientProtocol {

    /**
     * 客户端从服务器获取日志条目（获取数据）
     * @param request
     * @return
     * @throws Exception
     */
    CompletableFuture<GetEntriesResponse> get(GetEntriesRequest request) throws Exception;

    /**
     * 客户端向服务器追加日志（存储数据）
     * @param request
     * @return
     * @throws Exception
     */
    CompletableFuture<AppendEntryResponse> append(AppendEntryRequest request) throws Exception;

    /**
     * 获取元数据。
     * @param request
     * @return
     * @throws Exception
     */
    CompletableFuture<MetadataResponse> metadata(MetadataRequest request) throws Exception;

    /**
     * 处理领导层调动
     * @param request
     * @return
     * @throws Exception
     */
    CompletableFuture<LeadershipTransferResponse> leadershipTransfer(LeadershipTransferRequest request) throws Exception;
}
