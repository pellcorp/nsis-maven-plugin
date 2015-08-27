package org.codehaus.mojo.nsis;

public class Compression {
	public enum Type {
		zlib, bzip2, lzma
	}
	
	private Type type = Type.zlib;
	private int dictionarySize = 8;
	private boolean doFinal = false;
	private boolean doSolid = false;

	public Compression() {
	}
	
	public int getDictionarySize() {
		return dictionarySize;
	}

	public void setDictionarySize(int dictionarySize) {
		this.dictionarySize = dictionarySize;
	}

	public boolean isDefault() {
		if (!type.equals(Type.zlib)) {
			return false;
		}
		if (doFinal) {
			return false;
		}
		if (doSolid) {
			return false;
		}
		if (dictionarySize != 8) {
			return false;
		}
		
		return true;
	}
	
	public Type getType() {
		return type;
	}
	
	public void setType(Type type) {
		if (type != null) {
			this.type = type;
		} else {
			this.type = Type.zlib;
		}
	}
	
	public boolean isDoFinal() {
		return doFinal;
	}
	
	public void setDoFinal(boolean doFinal) {
		this.doFinal = doFinal;
	}
	
	public boolean isDoSolid() {
		return doSolid;
	}
	
	public void setDoSolid(boolean doSolid) {
		this.doSolid = doSolid;
	}
}
