package com.dfire.core.netty.worker;


import com.dfire.core.lock.DistributeLock;
import com.dfire.core.message.Protocol;
import com.dfire.core.schedule.ScheduleInfoLog;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.util.concurrent.*;


/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 10:34 2018/1/10
 * @desc
 */
@Slf4j
@Component
public class WorkClient {

    private Bootstrap bootstrap;
    private EventLoopGroup eventLoopGroup;
    private WorkContext workContext;

    @PostConstruct
    public void WorkClient() {
        workContext = new WorkContext();
        eventLoopGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new ProtobufVarint32FrameDecoder())
                                .addLast(new ProtobufDecoder(Protocol.SocketMessage.getDefaultInstance()))
                                .addLast(new ProtobufVarint32LengthFieldPrepender())
                                .addLast(new ProtobufEncoder())
                                .addLast(new WorkHandler(workContext));
                    }
                });
        log.info("start work client success ");
        workContext.setWorkClient(this);
        sendHeartBeat();
    }

    /**
     * work 向 master 发送心跳信息
     */
    private void sendHeartBeat() {
        ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
        service.scheduleAtFixedRate(new Runnable() {
            private int failCount = 0;

            @Override
            public void run() {
                if (workContext.getServerChannel() != null) {
                    WorkerHeartBeat heartBeat = new WorkerHeartBeat();
                    ChannelFuture channelFuture = heartBeat.send(workContext);
                    channelFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.info("send heart beat failed");
                            } else {
                                failCount++;
                                log.info("send heart beat success");
                            }
                        }
                    });
                } else {
                    log.info("server channel can not find on " + DistributeLock.host);
                }
            }

        }, 0, 5, TimeUnit.SECONDS);


    }

    public synchronized void connect(String host, int port) throws Exception {
        //首先判断服务频道是否开启
        if (workContext.getServerChannel() != null) {
            //如果需要通信的对象是自己 那么直接返回
            if (workContext.getServerHost().equals(host)) {
                return ;
            } else { //关闭之前通信
                workContext.getServerChannel().close();
                workContext.setServerChannel(null);
            }
        }
        workContext.setServerHost(host);
        CountDownLatch latch = new CountDownLatch(1);
        ChannelFutureListener futureListener = (future) -> {
                try {
                    if (future.isSuccess()) {
                        workContext.setServerChannel(future.channel());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
        };
        ChannelFuture connectFuture = bootstrap.connect(new InetSocketAddress(host, port));
        connectFuture.addListener(futureListener);
        if (!latch.await(2, TimeUnit.SECONDS)) {
            connectFuture.removeListener(futureListener);
            connectFuture.cancel(true);
            throw new ExecutionException(new TimeoutException("connect server consumption of 2 seconds"));
        }
        if (!connectFuture.isSuccess()) {
            throw new RuntimeException("connect server failed " + host,
                    connectFuture.cause());
        }
        ScheduleInfoLog.info("connect server success");
    }

}
