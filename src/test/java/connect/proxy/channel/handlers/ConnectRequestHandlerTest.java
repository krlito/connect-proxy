package connect.proxy.channel.handlers;

import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;


public class ConnectRequestHandlerTest {
    private static final int READ_TIMEOUT = 5000;

    @Test
    public void unreachableHost_serviceUnavailableResponse() throws Exception {
        EventLoopGroup evLoopGroup = new NioEventLoopGroup(1);

        try {
            String unreachableUri = "unreachable:31173";
            EmbeddedChannel inChannel = new EmbeddedChannel(
                    new ConnectRequestHandler(Collections.emptyList(),
                    (NioEventLoop) evLoopGroup.next()));
            FullHttpRequest inConnectReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, unreachableUri);

            inChannel.writeInbound(inConnectReq);
            FullHttpResponse outConnectReq = syncReadOutbound(inChannel, READ_TIMEOUT);
            Assert.assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, outConnectReq.status());

        } finally {
            evLoopGroup.shutdownGracefully();
        }
    }

    @Test
    public void invalidUri_channelIsClosed() throws Exception {
        EventLoopGroup evLoopGroup = new NioEventLoopGroup(1);

        try {
            String invalidUri = "unreachable";
            EmbeddedChannel inChannel = new EmbeddedChannel(
                    new ConnectRequestHandler(Collections.emptyList(),
                    (NioEventLoop) evLoopGroup.next()));
            FullHttpRequest inConnectReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, invalidUri);

            Assert.assertTrue(inChannel.isOpen());
            inChannel.writeInbound(inConnectReq);
            Assert.assertFalse(inChannel.isOpen());
        } finally {
            evLoopGroup.shutdownGracefully();
        }
    }

    // This test may fail sometimes; it seems there is a bug on EmbeddedChannel which is used for
    // testing. Still, this behavior is also tested on ProxyServerTest.
    @Test
    public void validConnectRequest_okResponseAndTunnel() throws Exception {
        String dstRequest = "ABC123";
        String dstResponse = "DEF456";
        
        EventLoopGroup evLoopGroup = new NioEventLoopGroup();
        ServerSocket dstServerSocket = new ServerSocket(0);
        int dstServerPort = dstServerSocket.getLocalPort();

        try {
            EmbeddedChannel inChannel = new EmbeddedChannel(
                    new ConnectRequestHandler(Collections.emptyList(),
                    (NioEventLoop) evLoopGroup.next()));
            FullHttpRequest inConnectReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, "localhost:" + dstServerPort);
            ByteBuf inTunnelMessage = Unpooled.buffer().writeBytes((dstRequest + "\r\n").getBytes());

            // Connect
            inChannel.writeInbound(inConnectReq);
            inChannel.flush();
            FullHttpResponse outConnectReq = syncReadOutbound(inChannel, READ_TIMEOUT);
            Assert.assertEquals(HttpResponseStatus.OK, outConnectReq.status());

            // Server accepting connection
            Socket dstClientSocket = dstServerSocket.accept();
            Scanner dstClientSocketIn = new Scanner(dstClientSocket.getInputStream());
            PrintWriter dstClientSocketOut = new PrintWriter(dstClientSocket.getOutputStream());

            // Message Interchange
            inChannel.writeInbound(inTunnelMessage);
            Assert.assertEquals(dstRequest, dstClientSocketIn.nextLine());

            dstClientSocketOut.print(dstResponse);
            dstClientSocketOut.flush();

            String outTunnelMessage = ((ByteBuf) syncReadOutbound(inChannel, READ_TIMEOUT))
                    .toString(Charset.forName("UTF-8"));
            Assert.assertEquals(dstResponse, outTunnelMessage);

        } finally {
            dstServerSocket.close();
            evLoopGroup.shutdownGracefully();
        }
    }

    private <T> T syncReadOutbound(EmbeddedChannel channel, int timeoutMs) throws Exception  {
        final int stepMs = 10;
        int elapsedMs = 0;

        T out = channel.readOutbound();

        while (out == null) {
            if (elapsedMs > timeoutMs) {
                throw new TimeoutException();
            }

            Thread.sleep(stepMs);
            elapsedMs += stepMs;
            out = channel.readOutbound();
        }

        return out;
    }

    private static int getRandomPort() {
        while (true) {
            int port = (new Random()).nextInt(1 << 14) + 1024;
            try {
                (new Socket("localhost", port)).close();
            } catch(Exception e) {
                return port;
            }
        }
    }
}
