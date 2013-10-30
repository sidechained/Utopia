Server.killAll
////////////////////////
// example based on github file

OSCFunc.trace

(
thisProcess.openUDPPort(3001);
~grahamPeer = Peer(\graham, NetAddr("192.168.2.1", 3001)); // put your IP here
// I'll use a different port and simulate a different machine
~addrBook = AddrBook().addMe(~grahamPeer);
Hail(~addrBook, me: ~grahamPeer);
~meClientID = 1;
)

~addrBook.peers

// here each participant creates and boots her server
(
~server = Server(\grahamServer, NetAddr("127.0.0.1", 57111), clientID:1);
~server.boot;
~server.makeWindow;
)

// here we will share server addresses
// OSCObjectSpace makes local copies of archivable objects stored remotely (see notes below)
(
~serverAddrs = OSCObjectSpace(~addrBook, oscPath:'/serverAddrs'); // me2's local copy
)

(
~meRemoteServers = IdentityDictionary.new; // here me1 will store her remote Server objects
~serverAddrs.addDependant({|objectSpace, what, key, val|
var newServer;
if(key != ~addrBook.me.name, {
"New Server!!".postln;
newServer = Server(key, val, clientID:~meClientID);
~meRemoteServers[key] = newServer;
SynthDescLib.global.addServer(newServer); // the remote server now gets any defs I add
});
});
)

~serverAddrs.put(\graham, ~server.addr); // me2 adds his Server addr; now me1 should have a local Server object referring to it

~serverAddrs.keys

/*
// we'll get updated each time a new desc is added remotely
// and SynthDescRelay adds the desc to the specified lib
(
~synthDescRel1 = SynthDescRelay(~addrBook1);
~lib2 = SynthDescLib(\lib2, ~server2); // fake a different default SynthDescLib on a different machine
~synthDescRel2 = SynthDescRelay(~addrBook2, libName:\lib2);
)
// me2 adds an action for new descs
(
~synthDescRel2.addDependant({|descRelay, what, desc, defcode|
"Check out this new def!!\n\n%\n".format(defcode).postln;
});
)

// simulate me1 making a def
SynthDef(\foo, {|freq = 440, dur = 1, amp = 0.1| Out.ar(0, SinOsc.ar(freq, 0, amp) * Env.sine(dur).kr(2));}).add;

// here we'll share some parameters
// OSCDataSpace allows OSC types only
// NB that Strings are converted to Symbols by SC's OSC implementation
(
~params1 = OSCDataSpace(~addrBook1, '/params');
~params2 = OSCDataSpace(~addrBook2, '/params');
)

// me2 sets the freq
~params2[\freq] = 880;

// me1 starts playing on me2's server
Pbind(\instrument, \foo, \freq, Pfunc({~params2[\freq]}), \server, ~me1RemoteServers[\me2]).play;

// me2 sets the freq which changes me1's stream
~params2[\freq] = 660;

// cleanup
(
~server1.quit;
~server2.quit;
~serverAddrs1.free;
~serverAddrs2.free;
~synthDescRel1.free;
~synthDescRel2.free;
~params1.free;
~params2.free;
)
*/