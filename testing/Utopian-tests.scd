// Utopian notes:

// PROBLEM: synthDefs are shared between langs but not between servers
// Q: how is this handled in the Examples, A: it isn't
// 2 options for this:
// - 1. set up a dependency on the SynthDescRelay which will send the synthdef to the local server
// - SynthDescRelay seems to support/suggest the first option, out of the box
// - 2. take a leaf from Republic: instead of sending the Synth to the language of each machine first, just send directly to the remote servers instead
// - to support this option would have to make changes to SynthDescRelay architecture

// how SynthDescRelay works
// it hooks onto \synthDescAdded dependency of SynthDescLib (lib is provided as an arg)
// when a new SynthDesc is added to the lib, it is converted to a def and sent to all peers except self as a compile string and as bytes
// receiving peers get the defCs, defBytes
// - bytes is used to reconstitute the synthdef
// - compileString is used as is
// problem is that we can't reconsitute the def itself
// we could compile the defcode, but that seems to defeat the point

// the reason for all the faffing is that the SynthDesc itself cannot be sent as an object (it contains frames)
// so instead we extract the def from the synthDesc and send that instead (cs and bytes)
// on the other end we reconstitute the synthDesc
// but this is not done properly it seems

// DONE: OSCObjectSpace and AbstractNode to get it to work with an address book that doesn't contain 'me' anymore
// DONE: OSCObjectSpace sync now pulls in properly
// TODO: SynthDescRelay only shares lang side representation of SynthDefs (SynthDescs), so Q: how to load them onto each server
// - in Republic synthDefs were added to each server in the
// - see republic.addSynthDef

// TODO: need to apply same class changes to OSCDataSpace
// TODO: seeing shared SynthDefs and sharing your own should be two separate things (separate flags?)
// TODO: test what happens when we add a synthdef before the Utopian (actually this makes no sense, there will no lib to add it to)
// DONE: ensured separate libs for each server (makes sharing SynthDefs possible without endless loopback)
// TODO: integrating shared server
// - TODO: Synth's do not by default play in the group of their own server
// - Q: what is the expected behaviour
// - when a remote SharedServer is created, it should produce a group on that server corresponding it it's own clientID
// - CHECK THAT each player has a separate group on the server
// - synths will play in these groups, but events will not
// - cmd. will free the peer's group across all servers
// - so need to be able to simulate Cmd. for each peer

Server.killAll;
//
u = Utopian(topology: \decentralised, verbose: false, hasAddrBookGui: true, seesServers: true, sharesSynthDefs: true);
u.server.queryAllNodes; // should contain groups for each peer
u.servers;
u.sharedServerAddrs.keys
u.sharedServerAddrs.values; // add a synthDef:
SynthDef(\twatBag, { Out.ar(0, SinOsc.ar(440)) }).add(u.node.me.id.asSymbol); // check what's in my lib:
SynthDescLib.all.at(u.node.me.id.asSymbol).synthDescs.keys;
// play some synths:
a = Synth(\twatBag, target: u.server.myGroup);
a.free;
b = Synth(\twatBag, target: u.servers.at(1).myGroup);
b.free;
c = Synth(\arseBag, target: u.server.myGroup);
c.free;
d = Synth(\arseBag, target: v.server.myGroup);
d.free;


//
v = Utopian(topology: \decentralised, verbose: false, hasAddrBookGui: true, seesServers: true, sharesSynthDefs: true);
v.server.queryAllNodes;
v.servers;
v.sharedServerAddrs.values;
SynthDef(\arseBag, { Out.ar(0, SinOsc.ar(880)) }).add(v.node.me.id.asSymbol); // add a synthDef:
SynthDescLib.all.at(v.node.me.id.asSymbol).synthDescs.keys; // check what's in my lib:
// play some synths:
e = Synth(\twatBag, target: v.server.myGroup);
e.free;
f = Synth(\twatBag, target: u.node.me.id);
f.free;
g = Synth(\arseBag, target: v.node.me.id);
g.free;
h = Synth(\arseBag, target: u.node.me.id);
h.free;