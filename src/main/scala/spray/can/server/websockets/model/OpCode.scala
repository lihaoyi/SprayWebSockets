package spray.can.server.websockets.model

import akka.util.ByteString
import java.nio.ByteBuffer

class OpCode(val value: Byte, val isControl: Boolean)
/**
 * All the opcodes a frame can have, defined in the spec
 *
 * http://tools.ietf.org/html/rfc6455
 */
object OpCode{

  val all = Map(
    0 -> Continuation,
    1 -> Text,
    2 -> Binary,
    8 -> ConnectionClose,
    9 -> Ping,
    10 -> Pong
  )

  case object Continuation extends OpCode(0, false)
  case object Text extends OpCode(1, false)
  case object Binary extends OpCode(2, false)
  case object ConnectionClose extends OpCode(8, true)
  case object Ping extends OpCode(9, true)
  case object Pong extends OpCode(10, true)

  val valid = List(Continuation, Text, Binary, ConnectionClose, Ping, Pong)
}


class CloseCode(val statusCode: Short){
  def toByteString = {
    val b = ByteBuffer.allocate(2)
    b.putShort(statusCode)
    ByteString(b.array())
  }
}

/**
 * All the connection closing status codes, defined in the spec
 *
 * http://tools.ietf.org/html/rfc6455
 */
object CloseCode{

  object NormalClosure extends CloseCode(1000)
  object GoingAway extends CloseCode(1001)
  object ProtocolError extends CloseCode(1002)
  object UnsupportedData extends CloseCode(1003)

  object NoStatusReceived extends CloseCode(1005)
  object AbnormalClosure extends CloseCode(1006)
  object InvalidFramePayloadData extends CloseCode(1007)
  object PolicyViolation extends CloseCode(1008)
  object MessageTooBig extends CloseCode(1009)
  object MandatoryExt extends CloseCode(1010)
  object InternalServerError extends CloseCode(1011)
  object TlsHandshake extends CloseCode(1015)
  val statusCodes = Seq(
    NormalClosure,
    GoingAway,
    ProtocolError,
    UnsupportedData,
    NoStatusReceived,
    AbnormalClosure,
    InvalidFramePayloadData,
    PolicyViolation,
    MessageTooBig,
    MandatoryExt,
    InternalServerError,
    TlsHandshake
  ).map(_.statusCode)
}