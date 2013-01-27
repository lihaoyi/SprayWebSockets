package spray.can.server.websockets

import model._
import org.scalatest.FreeSpec
import java.nio.ByteBuffer

class FrameTests extends FreeSpec{


  "serializing and deserializing should give you back the same thing" in {
    def checkFrame(f: Frame) = f === Frame.read(ByteBuffer.wrap(Frame.write(f)))
    checkFrame(Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am a cow".getBytes))
    checkFrame(Frame(false, (true, false, true), OpCode.Binary, Some(12345123), Array[Byte](1, 2, 3, 4, 5, 6)))
    checkFrame(Frame(false, (true, false, false), OpCode.Continuation, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
    checkFrame(Frame(false, (true, true, false), OpCode.Ping, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
    checkFrame(Frame(true, (false, true, true), OpCode.Pong, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
  }
}
