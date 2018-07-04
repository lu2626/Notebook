# netty4
源码分析版本为4.1.8.Final，重点在于分析netty服务端如何包装nio，从接收连接到返回的大致过程,所以源码只贴出重要的部分。在../source/nettydemo，它简单模拟了netty的流程，建议先看懂该项目，可有助于理解netty的源码重点部分的全貌。
## netty例子
```java
public static void main(String[] args) throws Exception {
    // bossGroup指定一个eventloop，一个eventloop为一个单线程组
    // eventloop本质是起一个selector做非阻塞的循环检查各个channel
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    // workerGroup默认是包含多个eventloop
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    // netty引导器，用来做初始配置与启动
    ServerBootstrap b = new ServerBootstrap();
    // 把bossGroup和workerGroup加入引导器中
    b.group(bossGroup, workerGroup)
            // 相关配置
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        // 加入handler处理进来的请求，实际填写逻辑的地方
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpRequestDecoder());
                        pipeline.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        pipeline.addLast(new HttpResponseEncoder());
                        pipeline.addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                                System.out.println("Received data");
                            }
                        });
                    }
                });
    // 绑定端口，是从这里启动的
    ChannelFuture f = b.bind(2048).sync();
    // 关闭
    f.channel().closeFuture().sync();
}
```
netty启动是从b.bind()开始

bind()->dobind()->initAndRegister()

```java
// AbstractBootstrap
private ChannelFuture doBind(final SocketAddress localAddress) {
    final ChannelFuture regFuture = initAndRegister();
    final Channel channel = regFuture.channel();
    if (regFuture.cause() != null) {
        return regFuture;
    }
    // 注册成功后调用doBind0()
    if (regFuture.isDone()) {
        // At this point we know that the registration was complete and successful.
        ChannelPromise promise = channel.newPromise();
        doBind0(regFuture, channel, localAddress, promise);
        return promise;
    }
}
```
## 第一部分：初始化与注册
initAndRegister()主要就是把channel注册到selector中
```java
final ChannelFuture initAndRegister() {
    Channel channel = null;
    try {
        // 根据之前b.channel(NioServerSocketChannel.class),反射生成NioServerSocketChannel实例
        channel = channelFactory.newChannel();
        init(channel);
    } catch (Throwable t) {
    }
    // 获取到channel， 传给eventLoopGroup中的eventLoop
    ChannelFuture regFuture = config().group().register(channel);
}
```
eventLoop就是一开始创建的NioEventLoop，继承自SingleThreadEventLoop类,来看该类的register方法
```java
//SingleThreadEventLoop
@Override
public ChannelFuture register(final ChannelPromise promise) {
    // 准备把channel注册到EventLoop的selector，这里的register(this, promise)实现是在AbstractChannel中
    promise.channel().unsafe().register(this, promise);
    return promise;
}
```
```java
// AbstractChannel
@Override
public final void register(EventLoop eventLoop, final ChannelPromise promise) {
    AbstractChannel.this.eventLoop = eventLoop;
    if (eventLoop.inEventLoop()) {
        register0(promise);
    } else {
        // 若不与eventloop线程相同，就交给eventLoop自己在合适的时机去做
        try {
            eventLoop.execute(new Runnable() {
                @Override
                public void run() {
                    register0(promise);
                }
            });
        } catch (Throwable t) {}
    }
}

private void register0(ChannelPromise promise) {
    try {
        // 此方法重点：注册
        doRegister();
    } catch (Throwable t) {
    }
}
```
doRegister()实际注册逻辑
```java
//AbstractNioChannel
@Override
protected void doRegister() throws Exception {
    boolean selected = false;
    for (;;) {
        try {
            // channel注册到eventLoop的selector
            selectionKey = javaChannel().register(eventLoop().selector, 0, this);
            return;
        } catch (CancelledKeyException e) {
            if (!selected) {
                eventLoop().selectNow();
                selected = true;
            } else {
                throw e;
            }
        }
    }
}
```
initAndRegister()这一部分算是结束了，虽然代码很多，也删减了很多，但主要功能就是通过启动ServerBootstrap为入口进行一些初始化与服务端相关的channel注册selector过程。
## 第二部分:绑定
接上文的AbstractBootstrap.dobind注册成功后，绑定是netty处理请求的核心逻辑，对应nio就是selector.select()所在循环内做的事情。

```java
private static void doBind0(
        final ChannelFuture regFuture, final Channel channel, final SocketAddress localAddress, final ChannelPromise promise) {
    channel.eventLoop().execute(new Runnable() {
        @Override
        public void run() {
            if (regFuture.isSuccess()) {
                channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            } else {
                promise.setFailure(regFuture.cause());
            }
        }
    });
}
```
channel.eventLoop()就是当前channel所拥有的eventLoop， NioEventLoop重写了run方法，它的父类SingleThreadEventExecutor重写了execute方法，并在execute方法调用了run方法，这里就开始了reactor线程所做的事情，也就是eventLoop的run方法。
```java
@Override
public void execute(Runnable task) {
    if (task == null) {
        throw new NullPointerException("task");
    }

    boolean inEventLoop = inEventLoop();
    // 本线程的加入任务
    if (inEventLoop) {
        addTask(task);
    } else {
        // 外部线程或初始化的时候开启eventLoop的run方法
        startThread();
        // 把任务加入
        addTask(task);
        if (isShutdown() && removeTask(task)) {
            reject();
        }
    }

    if (!addTaskWakesUp && wakesUpForTask(task)) {
        wakeup(inEventLoop);
    }
}
```
下面是处理
```java
//NioEventLoop
@Override
protected void run() {
    for (;;) {
        try {
            switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                case SelectStrategy.CONTINUE:
                    continue;
                case SelectStrategy.SELECT:
                    select(wakenUp.getAndSet(false));
                    if (wakenUp.get()) {
                        selector.wakeup();
                    }
                default:
                    // fallthrough
            }
            try {
                // 处理SelectedKeys
                processSelectedKeys();
            } finally {
                // Ensure we always run tasks.
                // 处理先前加入任务队列的所有任务
                runAllTasks();
            }
        } catch (Throwable t) {
            handleLoopException(t);
        }
    }
}
```
这里run方法是接收处理所有的channel类型，唤醒对应的阻塞的selector，处理所产生的SelectedKeys（这里的逻辑在../source/nettydemo项目中有简单的实现，建议先把简单的实现先看懂），最后把之前别的线程所产生积攒的任务，还有上篇说到的用户自己利用上下文传入的耗时task一起处理掉。
```java
//NioEventLoop
private void processSelectedKeys() {
    if (selectedKeys != null) {
        processSelectedKeysOptimized(selectedKeys.flip());
    } else {
        processSelectedKeysPlain(selector.selectedKeys());
    }
}
private void processSelectedKeysOptimized(SelectionKey[] selectedKeys) {
    for (int i = 0;; i ++) {
        final SelectionKey k = selectedKeys[i];
        if (k == null) {
            break;
        }
        // 移除已消费的key
        selectedKeys[i] = null;

        final Object a = k.attachment();

        if (a instanceof AbstractNioChannel) {
            processSelectedKey(k, (AbstractNioChannel) a);
        } else {
            @SuppressWarnings("unchecked")
            NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
            processSelectedKey(k, task);
        }
    }
}
// 实际处理每种类型的channel
private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
    final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
    try {
        int readyOps = k.readyOps();
        if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
            int ops = k.interestOps();
            ops &= ~SelectionKey.OP_CONNECT;
            k.interestOps(ops);
            unsafe.finishConnect();
        }
        if ((readyOps & SelectionKey.OP_WRITE) != 0) {
            ch.unsafe().forceFlush();
        }
        if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
            unsafe.read();
        }
    } catch (CancelledKeyException ignored) {
        unsafe.close(unsafe.voidPromise());
    }
}
```
unsafe是channel的一个代理类，这里可以直接认为是channel，重点看一下unsafe.read()实现

```java
@Override
public final void read() {
    final ChannelConfig config = config();
    final ChannelPipeline pipeline = pipeline();
    final ByteBufAllocator allocator = config.getAllocator();
    final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
    allocHandle.reset(config);
    ByteBuf byteBuf = null;
    boolean close = false;
    try {
        do {
            byteBuf = allocHandle.allocate(allocator);
            allocHandle.lastBytesRead(doReadBytes(byteBuf));
            if (allocHandle.lastBytesRead() <= 0) {
                // nothing was read. release the buffer.
                byteBuf.release();
                byteBuf = null;
                close = allocHandle.lastBytesRead() < 0;
                break;
            }
            allocHandle.incMessagesRead(1);
            readPending = false;
            // 重点
            pipeline.fireChannelRead(byteBuf);
            byteBuf = null;
        } while (allocHandle.continueReading());
        allocHandle.readComplete();
        pipeline.fireChannelReadComplete();
        if (close) {
            closeOnRead(pipeline);
        }
    } catch (Throwable t) {
        handleReadException(pipeline, byteBuf, t, close, allocHandle);
    } finally {
        if (!readPending && !config.isAutoRead()) {
            removeReadOp();
        }
    }
}
```
这里read的重点就是pipeline.fireChannelRead(byteBuf)，fireChannelRead()方法就是处理pipeline中入站的操作，也就是pipeline的入口，之后就是pipeline的一系列处理了，用户所写的handler逻辑在pipeline中被调用，完成一个请求的返回。