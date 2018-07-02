package com.cn;

import com.cn.pool.Boss;
import com.cn.pool.NioSelectorRunnablePool;

import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;

/**
 * 服务类
 *
 * @author luyunzhou.luke
 */
public class ServerBootstrap {

private NioSelectorRunnablePool selectorRunnablePool;
	
	ServerBootstrap(NioSelectorRunnablePool selectorRunnablePool) {
		this.selectorRunnablePool = selectorRunnablePool;
	}
	
	/**
	 * 绑定端口
	 * @param localAddress
	 */
	void bind(final SocketAddress localAddress){
		try {
			// 获得一个ServerSocket通道
			ServerSocketChannel serverChannel = ServerSocketChannel.open();
			// 设置通道为非阻塞
			serverChannel.configureBlocking(false);
			// 将该通道对应的ServerSocket绑定到port端口
			serverChannel.socket().bind(localAddress);
			
			//获取一个boss线程
			Boss nextBoss = selectorRunnablePool.nextBoss();
			//向boss注册一个ServerSocket通道
			nextBoss.registerAcceptChannelTask(serverChannel);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
