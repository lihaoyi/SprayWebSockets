package websockets.model

class OpCode(val value: Byte)
object OpCode{
  def apply(n: Int) = n match{
    case 0 => ContinuationFrame
    case 1 => TextFrame
    case 2 => BinaryFrame
    case 8 => ConnectionCloseFrame
    case 9 => PingFrame
    case 10 => PongFrame
  }
  object ContinuationFrame extends OpCode(0)
  object TextFrame extends OpCode(1)
  object BinaryFrame extends OpCode(2)
  object ConnectionCloseFrame extends OpCode(8)
  object PingFrame extends OpCode(9)
  object PongFrame extends OpCode(10)
}
class CloseCode(statusCode: Int)
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