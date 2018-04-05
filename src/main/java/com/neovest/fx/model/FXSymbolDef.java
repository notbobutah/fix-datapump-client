package com.neovest.fx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FXSymbolDef {
		public String id = "";
		public String symbolPair;
		public String emitterykey;
	    public long timeout;
		public int depthofbook;
		public ArrayList providers;
		public boolean agglist;

	 public FXSymbolDef() {
	 }
	 public FXSymbolDef(String id, String symbolpair)
	 {
		 this.id = id;
		 this.symbolPair = symbolpair;
		 this.depthofbook = 0;
	 }
	public FXSymbolDef(String id, String symbolpair, String emitterKey, int depthofbook)
	{
		this.id = id;
		this.symbolPair = symbolpair;
		this.emitterykey = emitterKey;
		this.depthofbook = depthofbook;
	}

	public FXSymbolDef(String id, String symbolpair, String emitterKey, int depthofbook, long timeout)
	{
		this.id = id;
		this.symbolPair = symbolpair;
		this.emitterykey = emitterKey;
		this.depthofbook = depthofbook;
		this.timeout = timeout;
	}

	public ArrayList getProviders() {
		return providers;
	}

	public void setProviders(ArrayList providers) {
		this.providers = providers;
	}
}
