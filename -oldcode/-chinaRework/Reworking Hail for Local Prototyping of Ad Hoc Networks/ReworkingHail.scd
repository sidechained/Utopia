



// now make into a class

)

// See Hail documentation, I didn't need to reverse engineer this!

// how a hail works:
// "a peer broadcasts a hail and receives a hail-reply from other peers"

// when created it:
// - initialises a dictionary of last responses
// - makes an out and in responder to respond to hails and hail replies, respectively
// - starts a hailing signal, which:
// -- regularly broadcasts a \hail message, at a given interval 'period', on multiple ports (57120 - 57127)
// -- if the period isn't nil, checkOnline is called, which sets peers online status to false if they were not heard from in twice the time of the period

// on remote peers (what about the local peer?), the outOSCFunc:
// - responds to the hail message, sending '\hail-reply peer.name' back to address it received from

// back on the sender, the inOSCFunc
// - checks to see if it has a last response from the peer with this name
// - if not, it:
// -- creates a new peer using the name
// -- adds the peer to local address book
// -- sets the peer's online status to true
// - if so, it:
// -- sets the peer's online status to true
// -- updates the peers last response time

// PROBLEM: the hail is not received, why not?
// the outOSCFunc will receive from any address, but on a specfic port


// what is OSCFunc.fix for?

OSCFunc.trace

// Simple example showing that local loopback works without an active network adapter
// disable ethernet and wifi before trying
// if can't get this example to work, try reloading supercollider (not recompiling!)

r = OSCFunc({ "message arrived".postln }, '/x');

n = NetAddr("127.0.0.1", 57120);
n.sendMsg("/x")

n.disconnect
r.free;

// Simple example showing that broadcasting doesn't NOT work without an active network adapter
// "errno49 no route to host" occurs with no active network adapter
// "errno13 permission denied" occurs with an active networka adapter if NetAddr.broadcastFlag has not been set to true

// if local then there's no need to broadcast

r = OSCFunc({ "message arrived".postln }, '/x');
NetAddr.broadcastFlag_(true);
m = NetAddr("255.255.255.255", 57120);
m.sendMsg("/x")

// can broadcast work?


// can i run more than one copy of the IDE?


"http://new-supercollider-mailing-lists-forums-use-these.2681727.n2.nabble.com/OSC-broadcast-td6929680.html"


//

// is opening ports a good strategy when using hail?
// perhaps hails are always designed to be received on the default SC in and out ports, assuming that each address will be different
// but when the address is the same, the port acts a differentiator

we need to use different ports in order to differentiate between
we also cannot use different hail namespaces as the whole point is that peers who want to register with each other need to use their own namespace

~addrBook1.me.addr.port

// as it works now, the in and out funcs only respond to messages received on the local peer's port
// the in and osc funcs assume that all peers will use the same port
// this works when the local peer's port is the default SC port i.e. 57120 (as specified by ~peer1 = Peer(\me1, NetAddr.localAddr));

// recvPort: addrBook.me.addr.port

// in a local prototyping context we cannot rely on the address of each peer to be different
// one method of differentiating between peers is by using different ports
// in this case we could open a different port for each peer, broadcast a \hail message using that port, and receive the \hail-reply back on that port
// this doesn't work in the current implementation as the

// can you see any potential problems if the in and out funcs are set to respond to messages from any port, not just the local peer's port?

// this mean modifying NetAddrMP
// what is the purpose of NetAddrMP and why does it have to broadcast on ports 57120?
