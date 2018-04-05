package net.badata.protobuf.converter.utils;

import java.lang.reflect.Type;

import com.google.protobuf.Message;

public class MessageClassTuple {
	public Class<? extends Message> v1;
	public Class<? extends Message> v2;

	@SuppressWarnings("unchecked")
	public MessageClassTuple(Type t1, Type t2) {
		v1 = (Class<? extends Message>) t1;
		v2 = (Class<? extends Message>) t2;
	}
	
}
