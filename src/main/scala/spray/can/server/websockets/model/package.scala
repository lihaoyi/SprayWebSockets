package spray.can.server.websockets

import java.nio.ByteBuffer
import akka.util.ByteString

package object model {
  implicit def byteArrayToBuffer(array: Array[Byte]) = ByteString(array)
}
