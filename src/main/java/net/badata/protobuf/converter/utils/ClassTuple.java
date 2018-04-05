package net.badata.protobuf.converter.utils;

import java.lang.reflect.Type;

public class ClassTuple {
	public Class<?> v1;
	public Class<?> v2;

	public ClassTuple(Type t1, Type t2) {
		v1 = (Class<?>) t1;
		v2 = (Class<?>) t2;
	}
	
}
