Testa {

	*new {
		^super.new.init;
	}
"/Users/grahambooth/Desktop/Testa.sc"
	init {
		this.startEventLoop([this.hello, this.world]);
	}

	hello {
		"hello".postln;
	}

	world {
		"world".postln;
	}

	startEventLoop {arg funcArray;
		Routine({
			inf.do{
				funcArray.do{arg func; func.value};
				1.wait;
			};
		}).play;
	}

}