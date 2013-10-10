package spray.can.server.websockets

import model._
import model.Frame.Successful
import org.scalatest.FreeSpec
import java.nio.ByteBuffer
import akka.util.ByteString
import util.Random
import java.io.{DataInputStream, ObjectInputStream, ByteArrayInputStream}

class FrameTests extends FreeSpec{
  implicit def byteArrayToBuffer(array: Array[Byte]) = ByteString(array)

  def checkFrame(f: Frame) = assert(Successful(f) === Frame.read(new DataInputStream(new ByteArrayInputStream(Frame.write(f).toArray))))

  "serializing and deserializing should give you back the same thing" in {

    checkFrame(Frame(true, 0, OpCode.Text, Some(12345123), "i am a cow".getBytes))
    checkFrame(Frame(false, 5, OpCode.Binary, Some(12345123), Array[Byte](1, 2, 3, 4, 5, 6)))
    checkFrame(Frame(false, 1, OpCode.Continuation, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
    checkFrame(Frame(false, 3, OpCode.Ping, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))
    checkFrame(Frame(true, 6, OpCode.Pong, None, Array[Byte](1, 2, 4, 8, 16, 32, 64)))


  }
  "fuzz testing" in {
    for (i <- 0 to 1024){
      import Random._
      checkFrame(Frame(
        nextBoolean(),
        nextInt(8).toByte,
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
