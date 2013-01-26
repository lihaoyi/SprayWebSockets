package spray.can.server.websockets

import java.nio.ByteBuffer

package object model {
  implicit def byteArrayToBuffer(array: Array[Byte]) = ByteBuffer.wrap(array)
}
