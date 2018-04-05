package com.neovest.fx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FXSymbolRequest {
		public String symbolPair;
	    public List<String> amounts;
		public List<String> provider;
		public int depthofbook;

	 public FXSymbolRequest() {
	 }
}
