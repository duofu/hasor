/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.land.election;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import net.hasor.core.EventListener;
import net.hasor.core.Init;
import net.hasor.core.Inject;
import net.hasor.core.future.FutureCallback;
import net.hasor.land.bootstrap.LandContext;
import net.hasor.land.domain.ServerStatus;
import net.hasor.land.node.NodeData;
import net.hasor.land.node.Operation;
import net.hasor.land.node.RunLock;
import net.hasor.land.node.Server;
import net.hasor.land.replicator.DataContext;
import net.hasor.land.utils.TermUtils;
import net.hasor.rsf.RsfContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * 选举服务,负责选出 Leader
 *
 * @version : 2016年09月10日
 * @author 赵永春(zyc@hasor.net)
 */
public class ElectionServiceManager implements ElectionService, EventListener<ServerStatus> {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    @Inject
    private Server        server;
    @Inject
    private DataContext   dataContext;
    @Inject
    private LandContext   landContext;
    @Inject
    private RsfContext    rsfContext;
    private AtomicBoolean landStatus;          // 在线状态
    //
    private AtomicBoolean followerTimer;       // follower 定时器
    private AtomicBoolean candidateTimer;      // candidate定时器
    private AtomicBoolean leaderTimer;         // leader   定时器
    //
    //
    @Init
    public void start() {
        this.landStatus = new AtomicBoolean(true);
        this.followerTimer = new AtomicBoolean(false);
        this.candidateTimer = new AtomicBoolean(false);
        this.leaderTimer = new AtomicBoolean(false);
        //
        this.landContext.addStatusListener(this);
        //
        this.startFollowerTimer();
        this.startCandidateTimer();
        this.startLeaderTimer();
        //
        this.landContext.fireStatus(ServerStatus.Follower);
    }
    //
    // --------------------------------------------------------------------------------------------
    // .状态切换事件
    //      当发生角色转换，负责更新各个定时器状态
    public void onEvent(String event, ServerStatus eventData) {
        this.followerTimer.set(false);
        this.candidateTimer.set(false);
        this.leaderTimer.set(false);
        //
        logger.info("Land[Status] - switchTo -> {}.", eventData);
        if (eventData == ServerStatus.Follower) {
            this.followerTimer.set(true);
            return;
        }
        // .一旦成为候选人那么马上进行选举
        if (eventData == ServerStatus.Candidate) {
            this.candidateTimer.set(true);
            return;
        }
        // .一旦成为 Leader 马上发送一个 term 以建立权威,然后通过心跳维持权威
        if (eventData == ServerStatus.Leader) {
            this.leaderTimer.set(true);
            return;
        }
    }
    // --------------------------------------------------------------------------------------------
    // .follower
    //      startFollowerTimer      启动定时器
    //      processFollowerTimer    定时器的循环调用
    //      processFollower         follower 逻辑代码
    private void startFollowerTimer() {
        if (!this.followerTimer.compareAndSet(false, true)) {
            this.logger.error("Land[Follower] - followerTimer -> already started");
            return;
        }
        this.logger.info("Land[Follower] - start followerTimer.");
        final long lastLeaderHeartbeat = this.server.getLastHeartbeat();
        this.landContext.atTime(new TimerTask() {
            public void run(Timeout timeout) throws Exception {
                processFollowerTimer(lastLeaderHeartbeat);
            }
        }, genTimeout());
    }
    private void processFollowerTimer(long lastLeaderHeartbeat) {
        // .如果系统退出，那么结束定时器循环
        if (!this.landStatus.get()) {
            return;
        }
        // .执行 Follower 任务
        try {
            this.processFollower(lastLeaderHeartbeat);
        } catch (Exception e) {
            logger.error("Land[Follower] - " + e.getMessage(), e);
        }
        // .重启定时器
        final long curLeaderHeartbeat = this.server.getLastHeartbeat();
        this.landContext.atTime(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                processFollowerTimer(curLeaderHeartbeat);
            }
        }, genTimeout());
    }
    private void processFollower(final long lastLeaderHeartbeat) {
        /* 确保 lockRun 中的方法在并发场景中是线程安全的 */
        this.server.lockRun(new RunLock() {
            public void run(Operation object) {
                if (!followerTimer.get()) {
                    return;
                }
                // .如果已经不在处于追随者,那么放弃后续处理
                if (object.getStatus() != ServerStatus.Follower) {
                    logger.info("Land[Follower] -> server mast be Follower, but ->" + object.getStatus());
                    return;
                }
                //
                // .判断启动定时器之后是否收到最新的 Leader 心跳 ,如果收到了心跳,那么放弃后续处理维持 Follower 状态
                //      (新的Leader心跳时间比启动定时器之前心跳时间要新,即为收到了心跳)
                boolean leaderHeartbeat = object.getLastHeartbeat() > lastLeaderHeartbeat;
                if (leaderHeartbeat) {
                    printLeader();
                    return;
                }
                //
                // .确保状态从 Follower 切换到 Candidate
                logger.info("Land[Follower] -> initiate the election.");
                if (object.getStatus() == ServerStatus.Follower) {
                    landContext.fireStatus(ServerStatus.Candidate);
                }
            }
        });
        //
    }
    // --------------------------------------------------------------------------------------------
    // .candidate
    //      startCandidateTimer     启动定时器
    //      processCandidateTimer   定时器的循环调用
    //      processCandidate        candidate 逻辑代码
    private void startCandidateTimer() {
        if (!this.candidateTimer.compareAndSet(false, true)) {
            this.logger.error("Land[Candidate] - candidateTimer -> already started");
            return;
        }
        this.logger.info("Land[Candidate] - start candidateTimer.");
        this.landContext.atTime(new TimerTask() {
            public void run(Timeout timeout) throws Exception {
                processCandidateTimer();
            }
        });
    }
    private void processCandidateTimer() {
        // .如果系统退出，那么结束定时器循环
        if (!this.landStatus.get()) {
            return;
        }
        // .执行 Candidate 任务
        try {
            this.processCandidate();
        } catch (Exception e) {
            logger.error("Land[Candidate] - " + e.getMessage(), e);
        }
        // .重启定时器
        this.landContext.atTime(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                processCandidateTimer();
            }
        }, genTimeout());
    }
    private void processCandidate() {
        /* 确保 lockRun 中的方法在并发场景中是线程安全的 */
        this.server.lockRun(new RunLock() {
            public void run(Operation object) {
                if (!candidateTimer.get()) {
                    return;
                }
                if (object.getStatus() != ServerStatus.Candidate) {
                    return;
                }
                // .候选人会继续保持着当前状态直到以下三件事情之一发生：
                //  (a) 他自己赢得了这次的选举，    -> 成为 Leader
                //  (b) 其他的服务器成为领导者，    -> 成为 Follower
                //  (c) 一段时间之后没有任何获胜的， -> 重新开始选举
                //
                //
                // .term自增
                object.incrementAndGetTerm();
                object.clearVoted();
                logger.info("Land[Candidate] -> solicit votes , current Trem is {}", object.getCurrentTerm());
                //
                // .发起选举然后收集选票
                List<NodeData> nodeList = object.getAllNodes();
                for (NodeData nodeData : nodeList) {
                    // .如果目标是自己，那么直接投给自己
                    if (nodeData.isSelf()) {
                        landContext.fireVotedFor(nodeData.getServerID());
                        object.applyVoted(nodeData.getServerID(), true);
                        continue;
                    }
                    // .征集选票（并发）
                    nodeData.collectVote(object, dataContext, new FutureCallback<CollectVoteResult>() {
                        public void completed(CollectVoteResult result) {
                            doVote(result);
                        }
                        public void failed(Throwable ex) {
                            if (ex.getCause() != null) {
                                ex = ex.getCause();
                            }
                            logger.error(ex.getMessage());
                        }
                        public void cancelled() {
                        }
                    });
                }
                //
                //
            }
        });
    }
    // --------------------------------------------------------------------------------------------
    // .leader
    //      startLeaderTimer        启动定时器
    //      processLeaderTimer      定时器的循环调用
    //      processLeader           leader 逻辑代码
    private void startLeaderTimer() {
        if (!this.leaderTimer.compareAndSet(false, true)) {
            this.logger.error("Land[Leader] - leaderTimer -> already started");
            return;
        }
        this.logger.info("Land[Leader] - start leaderTimer.");
        this.landContext.atTime(new TimerTask() {
            public void run(Timeout timeout) throws Exception {
                processLeaderTimer();
            }
        });
    }
    private void processLeaderTimer() {
        // .如果系统退出，那么结束定时器循环
        if (!this.landStatus.get()) {
            return;
        }
        // .执行 Leader 任务
        try {
            this.processLeader();
        } catch (Exception e) {
            logger.error("Land[Leader] - " + e.getMessage(), e);
        }
        // .重启定时器
        this.landContext.atTime(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                processLeaderTimer();
            }
        });
    }
    private void processLeader() {
        /* 确保 lockRun 中的方法在并发场景中是线程安全的 */
        this.server.lockRun(new RunLock() {
            public void run(Operation object) {
                if (!leaderTimer.get()) {
                    return;
                }
                //
                printLeader();
                //
                // .发送心跳log以维持 Leader 权威
                List<NodeData> nodeList = object.getAllNodes();
                for (NodeData nodeData : nodeList) {
                    //
                    // .如果心跳目标是自己，那么直接更新心跳时间
                    if (nodeData.isSelf()) {
                        object.newLastLeaderHeartbeat();
                        continue;
                    }
                    // .发送Leader心跳包（并发）
                    nodeData.leaderHeartbeat(object, dataContext, new FutureCallback<LeaderBeatResult>() {
                        public void completed(LeaderBeatResult result) {
                            doHeartbeat(result);
                        }
                        public void failed(Throwable ex) {
                            if (ex.getCause() != null) {
                                ex = ex.getCause();
                            }
                            logger.error(ex.getMessage());
                        }
                        public void cancelled() {
                        }
                    });
                }
            }
        });
    }
    // --------------------------------------------------------------------------------------------
    // .拉选票
    //      collectVote  处理拉票操作
    //      doVote       投票结果处理
    public void doVote(final CollectVoteResult voteData) {
        final String remoteTerm = voteData.getRemoteTerm();
        final String remoteServerID = voteData.getServerID();
        final boolean granted = voteData.isVoteGranted();
        //
        // .没有赢得选票，如果对方比自己大那么直接转换为 Follower
        this.server.lockRun(new RunLock() {
            public void run(Operation object) {
                String localTerm = object.getCurrentTerm();
                boolean gtFirst = TermUtils.gtFirst(localTerm, remoteTerm);
                if (!granted && gtFirst) {
                    logger.info("Land[Vote] -> this server follower to {}. L:R is {}:{}", remoteServerID, localTerm, remoteTerm);
                    object.updateTermTo(remoteTerm);
                    landContext.fireVotedFor(remoteServerID);
                    landContext.fireStatus(ServerStatus.Follower);
                    object.newLastLeaderHeartbeat();
                }
                //
                // .记录选票结果
                object.applyVoted(remoteServerID, voteData.isVoteGranted());
            }
            //
        });
        // .赢得了选票 -> 计票 -> 尝试成为 Leader
        if (granted) {
            this.server.lockRun(new RunLock() {
                public void run(Operation object) {
                    // .计票，尝试成为 Leader( 返回true表示赢得了这次的选举 )
                    List<NodeData> nodeList = object.getAllNodes();
                    int grantedCount = nodeList.size();
                    int serverCount = 0;
                    for (NodeData nodeData : nodeList) {
                        //                        if (nodeData.getStatus()) {
                        //                        }
                        serverCount++;
                        if (object.testVote(nodeData.getServerID())) {
                            grantedCount++;
                        }
                    }
                    boolean testToLeader = grantedCount * 2 > serverCount;
                    if (!testToLeader)
                        return;
                    //
                    landContext.fireVotedFor(landContext.getServerID());
                    landContext.fireStatus(ServerStatus.Leader);
                    logger.info("Land[Vote] -> this server is elected leader.");
                }
            });
            return;
        }
    }
    @Override
    public CollectVoteResult collectVote(CollectVoteData voteData) {
        final String selfTerm = this.server.getCurrentTerm();
        final String remoteTerm = voteData.getTerm();
        final String remoteServerID = voteData.getServerID();
        //
        final CollectVoteResult voteResult = new CollectVoteResult();
        voteResult.setServerID(this.landContext.getServerID());
        voteResult.setRemoteTerm(selfTerm);
        //
        // .无条件接受来自，自己的邀票
        if (this.landContext.getServerID().equals(remoteServerID)) {
            logger.info("Land[Vote] -> accept votes from self.");
            voteResult.setVoteGranted(true);
            return voteResult;
        }
        //
        // .处理拉票操作（线程安全）
        this.server.lockRun(new RunLock() {
            public void run(Operation object) {
                //
                // .如果远程的term比自己大，那么成为 Follower
                if (TermUtils.gtFirst(selfTerm, remoteTerm)) {
                    logger.info("Land[Vote] -> accept votes from {}.", remoteServerID);
                    voteResult.setVoteGranted(true);
                    object.updateTermTo(remoteTerm);
                    landContext.fireVotedFor(remoteServerID);
                    landContext.fireStatus(ServerStatus.Follower);
                    return;
                }
                // .拒绝投给他
                voteResult.setVoteGranted(false);
                logger.info("Land[Vote] -> reject to {} votes. cause: currentTerm({}) > remoteTerm({})", //
                        remoteServerID, selfTerm, remoteTerm);
                //
            }
        });
        return voteResult;
    }
    // --------------------------------------------------------------------------------------------
    // .Leader心跳
    //      leaderHeartbeat Leader进行心跳
    //      doHeartbeat     心跳结果处理
    @Override
    public LeaderBeatResult leaderHeartbeat(final LeaderBeatData beatResult) {
        //
        String targetServerID = beatResult.getServerID();
        LeaderBeatResult result = new LeaderBeatResult();
        result.setServerID(this.landContext.getServerID());
        if (!targetServerID.equals(this.server.getVotedFor())) {
            result.setAccept(false);
            return result;
        }
        //
        // .如果远程的term比自己大，那么更新term。更新心跳时间
        this.server.lockRun(new RunLock() {
            public void run(Operation object) {
                String selfTerm = server.getCurrentTerm();
                String targetTerm = beatResult.getTerm();
                if (TermUtils.gtFirst(selfTerm, targetTerm)) {
                    object.updateTermTo(targetTerm);
                }
                object.newLastLeaderHeartbeat();
            }
        });
        //
        result.setAccept(true);
        return result;
    }
    public void doHeartbeat(LeaderBeatResult leaderBeatResult) {
        //        String targetServerID = leaderBeatResult.getServerID();
        //        NodeData data = this.findTargetServer(targetServerID);
        //        if (data == null) {
        //            return null;
        //        }
        //        data.setNodeStatus(NodeStatus.Online);
        //        data.setVoteGranted(leaderBeatResult.isAccept());//可能失去了这个选票
        return;
    }
    // --------------------------------------------------------------------------------------------
    //
    /** 打印 Leader 信息 */
    private long lastPrintLeaderLog;
    private void printLeader() {
        //
        // .10秒打印一次 Leader 的心跳
        boolean printLeaderLog = this.lastPrintLeaderLog + 5000L < System.currentTimeMillis();
        if (printLeaderLog) {
            this.lastPrintLeaderLog = System.currentTimeMillis();
            this.logger.info("Land[Leader] -> leader is {} , term is {}", this.server.getVotedFor(), this.server.getCurrentTerm());
        }
    }
    /** 生成最长 (150 ~ 300) 的一个随机数,用作超时时间 */
    public int genTimeout() {
        return new Random(System.currentTimeMillis()).nextInt(150) + 150;
    }
}