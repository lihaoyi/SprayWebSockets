package spray.can.server.websockets

import model._
import model.Frame.Successful
import org.scalatest.FreeSpec
import java.nio.ByteBuffer
import akka.util.ByteString
import util.Random

class FrameTests extends FreeSpec{
  implicit def byteArrayToBuffer(array: Array[Byte]) = ByteString(array)

  def checkFrame(f: Frame) = assert(Successful(f) === Frame.read(Frame.write(f))._1)

  "serializing and deserializing should give you back the same thing" in {

    checkFrame(Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am a cow".getBytes))
    checkFrame(Frame(false, (true, false, true), OpCode.Binary, Some(12345123), Array[Byte](1, 2, 3, 4, 5, 6)))
    checkFrame(Frame(false, (true, false, false), OpCode.Continuation, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
    checkFrame(Frame(false, (true, true, false), OpCode.Ping, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
    checkFrame(Frame(true, (false, true, true), OpCode.Pong, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))


  }
  "fuzz testing" in {
    for (i <- 0 to 1024){
      import Random._
      checkFrame(Frame(
        nextBoolean(),
        (nextBoolean(), nextBoolean(), nextBoolean()),
        OpCode.valid(Random.nextInt(OpCode.valid.length)),
        maskingKey = nextBoolean() match{
          case false => None
          case true => Some(nextInt())
        },
        data = {
          val a = new Array[Byte](nextInt(100000))
          nextBytes(a)
          a
        }))
    }
  }


}
