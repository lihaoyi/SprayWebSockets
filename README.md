SprayWebSockets
===============

This is a implementation of a websocket server for the spray.io web toolkit. It is currently a work in progress, but it has a pretty comprehensive test suite that exercises [a whole bunch of functionality](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/test/scala/spray/can/server/websockets/SocketServerTests.scala), as well as a simple [demo applicatoin](http://www.textboxplus.com/). The current discussion thread is over [here](https://groups.google.com/forum/?fromgroups=#!topic/spray-user/KWlUhXs7kvs).

Getting Started
---------------

The basic workflow for taking an existing `HttpServer` application and making it support websockets is:

### Substitute the SocketServer in place of an existing HttpServer

The main class of interest is the [SocketServer](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/main/scala/spray/can/server/websockets/SocketServer.scala):

```scala
class SocketServer(httpHandler: MessageHandler,
                   frameHandler: Any => ActorRef,
                   settings: ServerSettings = ServerSettings(),
                   frameSizeLimit: Long = 1024 * 1024,
                   autoPingInterval: Duration = 1 second,
                   tickGenerator: () => Array[Byte] = {() => val a = new Array[Byte](128); Random.nextBytes(a); a})
                  (implicit sslEngineProvider: ServerSSLEngineProvider)
                   extends HttpServer(httpHandler, settings)
```

It is essentially an extended `HttpServer`. In fact it should be a drop-in replacement for a HttpServer: as long as you don't use any websocket functionality, its behavior should be identical. You can usea dummy `(x: Any) => null` `frameHandler` for now and everything should keep working.

The additional arguments are:

- `frameHandler`: A function that is used to find/create an actor to handle a websocket connection. More on this later
- `frameSizeLimit`: the largest frames that the server will buffer up for you. Anything larger and it'll dump the data and close the connection with a message-to-big error
- `autoPingInterval`: How often the server should send keep-alive pings
- `tickGenerator`: How the server should decide what to put in the body of those pings. Defaults to just a bunch of random bytes.


### Decide how you want to handle the websocket handshakes 

A websocket handshake is similar to an exchange of HttpRequest/Response, and the SocketServer re-uses all the existing http infrastructure to handle it. When a websocket request comes in, your `MessageHandler` will receive a `HttpRequest` which looks like

```
GET /mychat HTTP/1.1
Host: server.example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==
Sec-WebSocket-Protocol: chat
Sec-WebSocket-Version: 13
Origin: http://example.com
Server response:
```

This is the client half of the websocket handshake, which your `MessageHandler` will receive as a `HttpRequest`. If you want to accept it and upgrade into a websocket connection, you must reply with a `HttpResponse` which looks like

```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: HSmrc0sMlYUkAGmm5OPpG2HaGWk=
Sec-WebSocket-Protocol: chat
```

So far all this stuff is just normal usage of the `HttpServer`. This logic lives in your `MessageHandle`r actor's `receive()` method with the rest of your http handling stuff, and you can ignore/reject the request too if you don't want to handle it.

When you're done with the handshake, you must reply with an `SocketServer.Upgrade(data: Any)` message so the server can shift that connection into websocket-mode. The SocketServer will swap out the http-related pipeline with a websocket pipeline, and feed the `data` value into your provided `frameHandler` in order to find/create an actor to handle the websocket connection.

If you need help, look at the [AcceptActor](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/test/scala/spray/can/server/websockets/SocketServerTests.scala#L97-L104) in the unit tests to see how it does it. It uses a bunch of convenience methods that you could use to do the tedious bits (e.g. calculating hashes)

###Define a proper frameHandler 

The `frameHandler` takes the `data` from the `SocketServer.Upgrade` message and find/create an actor (let's call him the *Frame Handler*) to handle the frames from that connection.

The *Frame Handler* will then be sent a `SocketServer.Connected` message. The connection is now open, and the *Frame Handler* can now:

- Send `model.Frame` messages
- Receive `model.Frame` messages

to the sender of the `Connected` message. Each `Frame` is defined as:

```scala
case class Frame(FIN: Boolean = true,
                 RSV: (Boolean, Boolean, Boolean) = (false, false, false),
                 opcode: OpCode,
                 maskingKey: Option[Int] = None,
                 data: ByteString = ByteString.empty)
```

and represents a single websocket frame. Sending a `Frame` pipes it across the internet to whoever is on the other side, and any frames he pipes back will hit your *Frame Handler*'s `receive()` method. You've opened your first websocket connection! This is where the `SocketServer`'s job ends and your application can do whatever you want with the incoming `Frame`s.

###Close the Connection

In order to close the connection, the *Frame Handler* should send a frame with `opcode = OpCode.ConnectionClose` to comply with the websocket protocol, before sending a `SocketServer.Close` message to actually terminate the TCP connection. The *Frame Handler* will then receive a `SocketServer.Closed` message. If the client initiates a close (whether cleanly via a `ConnectionClose` frame, or by abruptly cutting off the TCP connection) the *Frame Handler* will just receive the `SocketServer.Closed` message directly.

More Stuff
----------

All the messages that the *Frame Handler* can expect to send/receive from the `SocketServer` are documented in the `SocketServer` [companion object](https://github.com/lihaoyi/SprayWebSockets/blob/master/src/main/scala/spray/can/server/websockets/SocketServer.scala#L102-L140).

The server also can

- Automatically ping every client according to the `autoPingInterval`, using the `tickGenerator` to generate the body of each ping
- Work under SSL (all the tests are done both in the clear and under SSL)
- Handle fragmented messages (the server will buffer up a complete message before passing it to your frameHandler
- Cut off messages which are too big (whether single-messages or fragmented)
- Automatically respond to pings with pongs
- Kill the connection when there's a protocol violation according to the websocket spec (probably doesn't account for everything at the moment)


