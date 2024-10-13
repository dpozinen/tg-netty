package com.dpozinen

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.inmo.krontab.doInfinity
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption.SO_KEEPALIVE
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.MessageToMessageEncoder
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.netty.handler.codec.http.HttpHeaderNames.CONNECTION
import io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH
import io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE
import io.netty.handler.codec.http.HttpHeaderNames.HOST
import io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON
import io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE
import io.netty.handler.codec.http.HttpMethod.POST
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder.forClient
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import kotlinx.coroutines.runBlocking
import java.lang.System.getenv
import java.net.URI

private const val API_TELEGRAM_ORG = "api.telegram.org"

private val tgHost = getenv().getOrDefault("TG_HOST", "https://$API_TELEGRAM_ORG")
private val tgPort = getenv().getOrDefault("TG_PORT", "443").toInt()
private val tgBotId = getenv().getOrDefault("TG_BOT_ID", "")
private val tgChatId = getenv().getOrDefault("TG_CHAT_ID", "")
private var cursor = getenv().getOrDefault("WORD_CURSOR", "0").toInt()
private val wordCount = getenv().getOrDefault("WORD_COUNT", "3").toInt()
private val wordCron = getenv().getOrDefault("WORD_CRON", "0 0 8 * *")

private val uri: URI = URI("$tgHost/bot$tgBotId/sendMessage")

private val words: Words = jacksonObjectMapper()
    .readValue({}.javaClass.getResource("/words.json"), Words::class.java)

fun main() {
    val eventLoopGroup = NioEventLoopGroup()

    try {
        with(Bootstrap()) {
            group(eventLoopGroup)
            channel(NioSocketChannel::class.java)
            option(SO_KEEPALIVE, true)

            handler(SocketChannelInitializer())

            begin { connect(uri.host, tgPort).sync().channel() }
        }
    } finally {
        eventLoopGroup.shutdownGracefully()
    }
}

fun begin(connect: () -> Channel) {
    var addRepeatedMessaged = false
    var channel = connect()

    channel.writeAndFlush(TgMessage("UP"))

    runBlocking {
        doInfinity(wordCron) {
            if (!channel.isActive) channel = connect()

            if (cursor >= words.count - wordCount - 1) {
                cursor = 0
                addRepeatedMessaged = true
            }

            val batch = words.words.subList(cursor, cursor + wordCount)
            cursor += wordCount

            if (addRepeatedMessaged) channel.write(TgMessage("⚠\uFE0F Word cycle complete"))

            println("Sending words: ${batch.map { it.french }}")

            channel.write(TgMessage("||cursor at $cursor||"))
            batch.forEach { word -> channel.write(word.asMessage()) }
            channel.flush()

            println("Current cursor: $cursor")
        }
    }
}

class SocketChannelInitializer(
    private val sslContext: SslContext = forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        ch.pipeline().addLast(
            sslContext.newHandler(ch.alloc(), uri.host, tgPort),
            HttpClientCodec(),
            TgResponseHandler(),
            TgRequestHandler(),
        )
    }
}

class TgRequestHandler : MessageToMessageEncoder<TgMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: TgMessage, out: MutableList<Any>) {
        val request = tgRequest(ctx, msg)
        with(request.headers()) {
            set(HOST, API_TELEGRAM_ORG)
            set(CONNECTION, KEEP_ALIVE)
            set(ACCEPT, "*/*")
            set(CONTENT_TYPE, APPLICATION_JSON)
            set(CONTENT_LENGTH, request.content().readableBytes())
        }
        out.add(request)
    }

    private fun tgRequest(ctx: ChannelHandlerContext, msg: TgMessage) =
        DefaultFullHttpRequest(
            HTTP_1_1,
            POST,
            uri.rawPath,
            ctx.alloc().directBuffer()
                .writeBytes(
                    """{"chat_id": "$tgChatId","text":"${msg.text}","parse_mode":"MarkdownV2"}""".toByteArray()
                ),
        )
}

class TgResponseHandler : SimpleChannelInboundHandler<HttpContent>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpContent) {
        println(msg.content().toString(CharsetUtil.UTF_8))
    }
}

private data class Words(val count: Int, val words: List<Word>)

private data class Word(val french: String, val english: String) {
    var escaped = "_*[]()~`>#+=|{}.!"

    fun asMessage() = TgMessage("${escape(french)} \\\\| ||${escape(english)}||")

    fun escape(text: String) = text
        .map { if (escaped.contains(it)) "\\\\$it" else it.toString() }
        .joinToString("")
}

data class TgMessage(val text: String)
