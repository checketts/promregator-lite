package org.cloudfoundry.promregator.cfaccessor

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LoggingHandler
import io.netty.util.internal.PlatformDependent.allocateUninitializedArray
import java.nio.charset.Charset
import java.nio.charset.Charset.defaultCharset
import java.lang.Math.max


data class CustomLogger(val clazz: Class<*>?) : LoggingHandler(clazz) {
    override fun format(ctx: ChannelHandlerContext?, event: String?, arg: Any?): String {
        if (arg is ByteBuf) {
            return decode(arg, arg.readerIndex(), arg.readableBytes(), defaultCharset())
        }
        return super.format(ctx, event, arg)
    }

    private fun decode(src: ByteBuf, readerIndex: Int, len: Int, charset: Charset): String {
        if (len != 0) {
            val array: ByteArray
            val offset: Int
            if (src.hasArray()) {
                array = src.array()
                offset = src.arrayOffset() + readerIndex
            } else {
                array = allocateUninitializedArray(max(len, 1024))
                offset = 0
                src.getBytes(readerIndex, array, 0, len)
            }
            return String(array, offset, len, charset)
        }
        return ""
    }
}