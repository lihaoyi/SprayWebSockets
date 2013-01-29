package spray.can.server.websockets

import model.Frame
import spray.io.{Command, Event}


case class FrameEvent(f: Frame) extends Event
case class FrameCommand(frame: Frame) extends Command
case class Upgrade(data: Any) extends Command