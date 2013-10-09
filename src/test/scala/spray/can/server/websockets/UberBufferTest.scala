package spray.can.server.websockets

import model._
import model.Frame.Successful
import org.scalatest.FreeSpec
import akka.util.ByteString
import util.Random

class UberBufferTest extends FreeSpec{
  "manual testing" in {
    val buf = new UberBuffer(1)
    val out = new Array[Byte](52)

    buf.write(ByteString("abcdefghijklmnopqrstuvwxyz"))

    buf.readTo(out, 0, 10)
    buf.readTo(out, 10, 10)
    assert(buf.capacity == 32)

    buf.write(ByteString("abcdefghijklmnopqrstuvwxyz"))
    assert(buf.capacity == 64)

    buf.readTo(out, 20, 10)

    buf.readTo(out, 30, 10)

    buf.readTo(out, 40, 10)

    buf.readTo(out, 50, 2)

    val desired = ByteString("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz")
    assert(ByteString(out) == desired)
  }
  " serializing and deserializing should give you back the same thing" in {
    for{
      bufferSize <- Seq(1, 41, 129, 1234)
      inputSize <- Seq(123, 412, 12239, 1234151)
    }{

      val buf = new UberBuffer(bufferSize)
      val data = new Array[Byte](inputSize)
      val out = new Array[Byte](inputSize)
      scala.util.Random.nextBytes(data)

      var currentWrite = 0
      var currentRead = 0

      while (currentRead < data.length){
        {

          val incr = math.min(scala.util.Random.nextInt(100), data.length-currentWrite)
          buf.write(ByteString.fromArray(data, currentWrite, incr))
          currentWrite += incr
        }

        val incr = math.min(scala.util.Random.nextInt(buf.readAvailable + 1), data.length-currentRead + 1)
        buf.readTo(out, currentRead, incr)
        currentRead += incr
      }

      assert(ByteString(out) == ByteString(data))
    }
  }
}
