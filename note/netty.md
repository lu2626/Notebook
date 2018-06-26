# netty
在介绍netty之前，可以先回顾一下java原生nio包是如何编写服务端的，这样可以体现出netty的优势。
## nio复习
java原生的nio底层内容暴露的比较多，不利于快速编写业务逻辑，以下面代码为例：
```java
public class NIOServer {

    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel ssChannel = ServerSocketChannel.open();
        // 设置为非阻塞，即accept为非阻塞
        ssChannel.configureBlocking(false);
        // 把ServerSocketChannel注册到selector上，并且指定SelectionKey.OP_ACCEPT，表示只接受连接,不处理数据。
        ssChannel.register(selector, SelectionKey.OP_ACCEPT);

        ServerSocket serverSocket = ssChannel.socket();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8888);
        serverSocket.bind(address);

        while (true) {
            // 轮询阻塞，直到有server的accept或client的read事件产生
            selector.select();
            // keys包含所有有事件的channel
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = keys.iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isAcceptable()) {
                    // 有客户端进行tcp连接
                    ServerSocketChannel ssChannel1 = (ServerSocketChannel) key.channel();
                    // 服务器会为每个新连接创建一个 SocketChannel
                    SocketChannel sChannel = ssChannel1.accept();
                    // 设置为非阻塞
                    sChannel.configureBlocking(false);
                    // 这个新连接主要用于从客户端读取数据，即制定SelectionKey.OP_READ
                    sChannel.register(selector, SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    // 处理客户端发来的报文
                    SocketChannel sChannel = (SocketChannel) key.channel();
                    System.out.println(readDataFromSocketChannel(sChannel));
                    sChannel.close();
                }
                // 需要把处理完的channel删除，防止重复处理
                keyIterator.remove();
            }
        }
    }

    private static String readDataFromSocketChannel(SocketChannel sChannel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        StringBuilder data = new StringBuilder();
        while (true) {
            buffer.clear();
            int n = sChannel.read(buffer);
            if (n == -1) {
                break;
            }
            buffer.flip();
            int limit = buffer.limit();
            char[] dst = new char[limit];
            for (int i = 0; i < limit; i++) {
                dst[i] = (char) buffer.get(i);
            }
            data.append(dst);
            buffer.clear();
        }
        return data.toString();
    }
}
```
有很多逻辑在写服务端时基本是不变的，例如创建ServerSocketChannel，设置成OP_ACCEPT事件并注册到selector，一旦有新连接进来，创建一个SocketChannel，设置为OP_READ事件并注册到selector，在轮询中通过selector不断获取SelectionKey的集合，去处理接受建立新的连接或处理连接发来的请求。对于这些我们不关心的，逻辑不怎么变动的代码，netty就进行了一次很好封装。
### netty代码例子：
```java
public static void main(String[] args) throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
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
    ChannelFuture f = b.bind(2048);
    f.addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
                System.out.println("Server bound");
            } else {
                System.err.println("bound fail");
                future.cause().printStackTrace();
            }
        }
    });
}
```
#### ServerBootstrap
一个netty的引导器，后面所说的所有配置都通过它来配。

#### EventLoopGroup
EventLoopGroup是EventLoop的一个集合，EventLoop在netty中是处理客户端新来的连接或连接传来的数据，可以认为一个EventLoop就需起一个线程，相当于一个selector，不过他把ServerSocketChannel或SocketChannel的创建、注册、模式都做了。为了性能考虑一般建两个EventLoopGroup，分别为bossGroup和workerGroup，也就是对于OP_ACCEPT事件和OP_READ事件的channel做分别处理。

其结构就是：EventLoopGroup 一 对 多 EventLoop，EventLoop 一 对 多 channel，而一个channel就是一个连接或请求。

在bossGroup中一般就定一个EventLoop，主要是处理OP_ACCEPT事件，把获取的SocketChannel交给workerGroup。对于workerGroup，一般会有多个EventLoop,workerGroup会把接收到的SocketChannel通过next()方法分配到某个EventLoop。
<div align="center"> <img src="../pics//EventLoopGroup.png1"/> </div><br>

#### ChannelPipeline
在workerGroup中把OP_READ事件接收后，会发送到ChannelPipeline中，ChannelPipeline与channel的关系是一对一， 设计思想类似于责任链模式，这里对于请求的处理相当于springmvc中的处理器拦截器。ChannelPipeline维护着一个ChannelHandler的链表队列,在Netty中关于ChannelHandler有两个重要的接口，ChannelInBoundHandler和ChannelOutBoundHandler。inbound可以理解为网络数据从外部流向系统内部，而outbound可以理解为网络数据从系统内部流向系统外部。用户实现的ChannelHandler可以根据需要实现其中一个或多个接口，将其放入Pipeline中的链表队列中，ChannelPipeline会根据不同的IO事件类型来找到相应的Handler来处理，同时链表队列是责任链模式的一种变种，自上而下或自下而上所有满足事件关联的Handler都会对事件进行处理。


















