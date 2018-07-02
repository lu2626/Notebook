package com.cn.pool;

import java.nio.channels.ServerSocketChannel;

/**
 * boss接口
 *
 * @author luyunzhou.luke
 */
public interface Boss {
	
	/**
	 * 加入一个新的ServerSocket
	 * @param serverChannel
	 */
	void registerAcceptChannelTask(ServerSocketChannel serverChannel);
}
