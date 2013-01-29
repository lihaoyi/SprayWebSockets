package spray.can.server.websockets.model

class OpCode(val value: Byte)
object OpCode{
  def apply(n: Int) = n match{
    case 0 => Continuation
    case 1 => Text
    case 2 => Binary
    case 8 => ConnectionClose
    case 9 => Ping
    case 10 => Pong
  }
  object Continuation extends OpCode(0)
  object Text extends OpCode(1)
  object Binary extends OpCode(2)
  object ConnectionClose extends OpCode(8)
  object Ping extends OpCode(9)
  object Pong extends OpCode(10)
}
class CloseCode(val statusCode: Short)
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
}