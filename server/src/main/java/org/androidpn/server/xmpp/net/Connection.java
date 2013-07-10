/*
 * Copyright (C) 2010 Moduad Co., Ltd.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.androidpn.server.xmpp.net;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.security.KeyStore;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.androidpn.server.util.Config;
import org.androidpn.server.xmpp.session.Session;
import org.androidpn.server.xmpp.ssl.SSLConfig;
import org.androidpn.server.xmpp.ssl.SSLKeyManagerFactory;
import org.androidpn.server.xmpp.ssl.SSLTrustManagerFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.ssl.SslFilter;
import org.dom4j.io.OutputFormat;
import org.jivesoftware.util.XMLWriter;
import org.xmpp.packet.Packet;

/**
 * This class represents a XMPP connection on the server.
 * 
 * 和服务器建立的连接
 * 
 * @author Sehwan Noh (devnoh@gmail.com)
 */
public class Connection {

	private static final Log log = LogFactory.getLog(Connection.class);

	private IoSession ioSession;

	private Session session;

	/** 连接关闭监视器 */
	private ConnectionCloseListener closeListener;

	private int majorVersion = 1;

	private int minorVersion = 0;

	private String language = null;

	/** TLS证书策略 */
	private TLSPolicy tlsPolicy = TLSPolicy.optional;

	@SuppressWarnings("rawtypes")
	private static ThreadLocal encoder = new ThreadLocalEncoder();

	/** 是否关闭 */
	private boolean closed;

	/**
	 * Constructor.
	 * 
	 * @param ioSession the IoSession
	 */
	public Connection(IoSession ioSession) {
		this.ioSession = ioSession;
		this.closed = false;
	}

	//    /**
	//     * Verifies that the connection is still live.
	//     * 
	//     * @return true if the socket remains valid, false otherwise.
	//     */
	//    public boolean validate() {
	//        if (isClosed()) {
	//            return false;
	//        }
	//        deliverRawText(" ");
	//        return !isClosed();
	//    }

	/**
	 * Closes the session including associated socket connection,
	 * notifing all listeners that the channel is shutting down.
	 * 
	 * 关闭所有与本连接有关的session，并通知这个通道的所有监听器
	 * 
	 */
	public void close() {
		boolean closedSuccessfully = false;
		synchronized (this) {
			if (!isClosed()) {
				try {
					// 发送服务器结束符
					deliverRawText("</stream:stream>", false);
				} catch (Exception e) {
					// Ignore
				}
				if (session != null) {
					// 设置会话为关闭状态
					session.setStatus(Session.STATUS_CLOSED);
				}
				ioSession.close(false);
				closed = true;
				closedSuccessfully = true;
			}
		}
		if (closedSuccessfully) {
			// 成功关闭会话，则通知关闭监听器
			notifyCloseListeners();
		}
	}

	/**
	 * Sends notification message indicating that the server is being shutdown.
	 * 向客户端发送服务器关闭的消息
	 * 
	 */
	public void systemShutdown() {
		deliverRawText("<stream:error><system-shutdown "
				+ "xmlns='urn:ietf:params:xml:ns:xmpp-streams'/></stream:error>");
		close();
	}

	/**
	 * Initializes the connection with it's owning session.
	 * 
	 * @param session the session that owns this connection
	 */
	public void init(Session session) {
		this.session = session;
	}

	/**
	 * Returns true if the connection is closed.
	 * 
	 * @return true if the connection is closed, false otherwise.
	 */
	public boolean isClosed() {
		if (session == null) {
			return closed;
		}
		return session.getStatus() == Session.STATUS_CLOSED;
	}


	/**
	 * Registers a listener for close event notification.
	 * 
	 * 注册会话关闭监听器
	 * 
	 * @param listener the listener to register for close events.
	 */
	public void registerCloseListener(ConnectionCloseListener listener) {
		if (closeListener != null) {
			throw new IllegalStateException("Close listener already configured");
		}
		if (isClosed()) {
			listener.onConnectionClose(session);
		} else {
			closeListener = listener;
		}
	}

	/**
	 *  Removes a registered close event listener.
	 *  
	 * @param listener the listener to unregister for close events.
	 */
	public void unregisterCloseListener(ConnectionCloseListener listener) {
		if (closeListener == listener) {
			closeListener = null;
		}
	}

	private void notifyCloseListeners() {
		if (closeListener != null) {
			try {
				closeListener.onConnectionClose(session);
			} catch (Exception e) {
				log.error("Error notifying listener: " + closeListener, e);
			}
		}
	}

	/**
	 * Delivers the packet to this connection (without checking the recipient).
	 * 
	 * @param packet the packet to deliver
	 */
	public void deliver(Packet packet) {
		log.debug("SENT: " + packet.toXML());
		// connection 没有被关闭
		if (!isClosed()) {
			// 初始化缓冲区
			IoBuffer buffer = IoBuffer.allocate(4096);
			buffer.setAutoExpand(true);
			
			// 发送错误标志位
			boolean errorDelivering = false;
			try {
				// 初始化xml输出器
				XMLWriter xmlSerializer = new XMLWriter(new IoBufferWriter(buffer, (CharsetEncoder) encoder.get()),
						new OutputFormat());
				xmlSerializer.write(packet.getElement());
				xmlSerializer.flush();
				buffer.flip();
				// 向客户端回写信息包
				ioSession.write(buffer);
			} catch (Exception e) {
				log.debug("Connection: Error delivering packet" + "\n" + this.toString(), e);
				errorDelivering = true;
			}
			// 发生错误时
			if (errorDelivering) {
				close();
			} else {
				// 服务器发送的包计数+1
				session.incrementServerPacketCount();
			}
		}
	}

	/**
	 * Delivers raw text to this connection (in asynchronous mode).
	 * 
	 * @param text the XML stanza string to deliver
	 */
	public void deliverRawText(String text) {
		deliverRawText(text, true);
	}

	private void deliverRawText(String text, boolean asynchronous) {
		log.debug("SENT: " + text);
		if (!isClosed()) {
			IoBuffer buffer = IoBuffer.allocate(text.length());
			buffer.setAutoExpand(true);

			boolean errorDelivering = false;
			try {
				buffer.put(text.getBytes("UTF-8"));
				buffer.flip();
				if (asynchronous) {
					ioSession.write(buffer);
				} else {
					// Send stanza and wait for ACK
					boolean ok = ioSession.write(buffer).awaitUninterruptibly(
							Config.getInt("connection.ack.timeout", 2000));
					if (!ok) {
						log.warn("No ACK was received when sending stanza to: " + this.toString());
					}
				}
			} catch (Exception e) {
				log.debug("Connection: Error delivering raw text" + "\n" + this.toString(), e);
				errorDelivering = true;
			}
			// Close the connection if delivering text fails
			if (errorDelivering && asynchronous) {
				close();
			}
		}
	}

	public void startTLS(ClientAuth authentication) throws Exception {
		log.debug("startTLS()...");
		KeyStore ksKeys = SSLConfig.getKeyStore();
		String keypass = SSLConfig.getKeyPassword();

		KeyStore ksTrust = SSLConfig.getc2sTrustStore();
		String trustpass = SSLConfig.getc2sTrustPassword();

		KeyManager[] km = SSLKeyManagerFactory.getKeyManagers(ksKeys, keypass);
		TrustManager[] tm = SSLTrustManagerFactory.getTrustManagers(ksTrust, trustpass);

		SSLContext tlsContext = SSLContext.getInstance("TLS");
		tlsContext.init(km, tm, null);

		SslFilter filter = new SslFilter(tlsContext);
		ioSession.getFilterChain().addFirst("tls", filter);
		//ioSession.getFilterChain().addBefore("executor", "tls", filter);
		ioSession.setAttribute(SslFilter.DISABLE_ENCRYPTION_ONCE, Boolean.TRUE);

		deliverRawText("<proceed xmlns=\"urn:ietf:params:xml:ns:xmpp-tls\"/>");
	}

	/**
	 * Returns the IP address.
	 * 
	 * 取得hostIP地址
	 * 
	 * @return the IP address
	 * @throws UnknownHostException if IP address of host could not be determined.
	 */
	public String getHostAddress() throws UnknownHostException {
		return ((InetSocketAddress) ioSession.getRemoteAddress()).getAddress().getHostAddress();
	}

	/**
	 * Gets the host name for the IP address.
	 * 
	 * 取得主机的HOST名称
	 * 
	 * @return the host name for this IP address
	 * @throws UnknownHostException  if IP address of host could not be determined.
	 */
	public String getHostName() throws UnknownHostException {
		return ((InetSocketAddress) ioSession.getRemoteAddress()).getAddress().getHostName();
	}

	/**
	 * Returns the major version of XMPP being used by this connection.
	 * 
	 * @return the major XMPP version
	 */
	public int getMajorXMPPVersion() {
		return majorVersion;
	}

	/**
	 * Returns the minor version of XMPP being used by this connection.
	 * 
	 * @return the minor XMPP version
	 */
	public int getMinorXMPPVersion() {
		return minorVersion;
	}

	/**
	 * Sets the XMPP version information.
	 * 
	 * @param majorVersion the major version
	 * @param minorVersion the minor version
	 */
	public void setXMPPVersion(int majorVersion, int minorVersion) {
		this.majorVersion = majorVersion;
		this.minorVersion = minorVersion;
	}

	/**
	 * Returns the language code that should be used for this connection.
	 * 
	 * 取得连接所有的语言
	 * 
	 * @return the language code
	 */
	public String getLanguage() {
		return language;
	}

	/**
	 * Sets the language code that should be used for this connection.
	 * 
	 * 设置连接所用的语言
	 * 
	 * @param language the language code
	 */
	public void setLanaguage(String language) {
		this.language = language;
	}

//	@SuppressWarnings("rawtypes")
	private static class ThreadLocalEncoder extends ThreadLocal<Object> {
		protected Object initialValue() {
			return Charset.forName("UTF-8").newEncoder();
		}
	}

	public TLSPolicy getTlsPolicy() {
		return tlsPolicy;
	}

	public void setTlsPolicy(TLSPolicy tlsPolicy) {
		this.tlsPolicy = tlsPolicy;
	}

	/**
	 * Enumeration of possible TLS policies required to interact with the server.
	 * 
	 * 和交互时SSL使用的策略
	 */
	public enum TLSPolicy {
		required, optional, disabled
	}

	/**
	 * Enumeration that specifies if clients should be authenticated (and how)
	 * while negotiating TLS.
	 * 
	 * 当进行TLS握手建立连接时，指定客户端是否需要被认证
	 */
	public enum ClientAuth {
		disabled, wanted, needed
	}

}
