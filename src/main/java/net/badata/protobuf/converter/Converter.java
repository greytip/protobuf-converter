/*
 * Copyright (C) 2016  BAData Creative Studio
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package net.badata.protobuf.converter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.protobuf.Message;

import net.badata.protobuf.converter.annotation.ProtoClass;
import net.badata.protobuf.converter.exception.ConverterException;
import net.badata.protobuf.converter.exception.MappingException;
import net.badata.protobuf.converter.exception.TypeRelationException;
import net.badata.protobuf.converter.exception.WriteException;
import net.badata.protobuf.converter.mapping.Mapper;
import net.badata.protobuf.converter.mapping.MappingResult;
import net.badata.protobuf.converter.resolver.FieldResolver;
import net.badata.protobuf.converter.resolver.FieldResolverFactory;
import net.badata.protobuf.converter.utils.AnnotationUtils;
import net.badata.protobuf.converter.utils.ClassTuple;
import net.badata.protobuf.converter.utils.FieldUtils;
import net.badata.protobuf.converter.utils.MessageClassTuple;
import net.badata.protobuf.converter.utils.MessageUtils;
import net.badata.protobuf.converter.writer.DomainWriter;
import net.badata.protobuf.converter.writer.ProtobufWriter;

/**
 * Converts data from Protobuf messages to domain model objects and vice versa.
 *
 * @author jsjem
 */
public final class Converter {

	private final Configuration configuration;

	/**
	 * Create default converter.
	 *
	 * @return Converter instance.
	 */
	public static Converter create() {
		return create(Configuration.builder().build());
	}

	/**
	 * Create converter with map of ignored fields.
	 *
	 * @param fieldsIgnore Map of fields that has to be ignored by this converter instance.
	 * @return Converter instance.
	 * @deprecated use {@code create(Configuration)} instead.
	 */
	@Deprecated
	public static Converter create(final FieldsIgnore fieldsIgnore) {
		Configuration.Builder configurationBuilder = Configuration.builder();
		configurationBuilder.setIgnoredFields(fieldsIgnore);
		return new Converter(configurationBuilder.build());
	}

	/**
	 * Create configured converter.
	 *
	 * @param configuration Parameters for conversion.
	 */
	public static Converter create(final Configuration configuration) {
		return new Converter(configuration);
	}

	/**
	 * Create object that performing conversion from protobuf object to domain model object and vice versa.
	 *
	 * @param configuration Parameters for conversion.
	 */
	private Converter(final Configuration configuration) {
		if (configuration == null) {
			throw new IllegalArgumentException("Argument configuration can't be null");
		}
		this.configuration = configuration;
	}

	/**
	 * Create domain object list from Protobuf dto list.
	 *
	 * @param domainClass        Expected domain object type.
	 * @param protobufCollection Source instance of Protobuf dto collection.
	 * @param <T>                Domain type.
	 * @param <E>                Protobuf dto type.
	 * @return Domain objects list filled with data stored in the Protobuf dto list.
	 */
	@SuppressWarnings("unchecked")
	public <T, E extends Message> List<T> toDomain(final Class<T> domainClass, final Collection<E>
			protobufCollection) {
		return toDomain(List.class, domainClass, protobufCollection);

	}

	@SuppressWarnings("unchecked")
	private <T, E extends Message, K extends Collection> K toDomain(final Class<K> collectionClass,
			final Class<T> domainClass, final Collection<E> protobufCollection) {
		Collection<T> domainList = List.class.isAssignableFrom(collectionClass) ? new ArrayList<T>() : new
				HashSet<T>();
		for (E protobuf : protobufCollection) {
			domainList.add(toDomain(domainClass, protobuf));
		}
		return (K) domainList;
	}

	/**
	 * Create domain object from Protobuf dto.
	 *
	 * @param domainClass Expected domain object type.
	 * @param protobuf    Source instance of Protobuf dto bounded to domain.
	 * @param <T>         Domain type.
	 * @param <E>         Protobuf dto type.
	 * @return Domain instance filled with data stored in the Protobuf dto.
	 */
	public <T, E extends Message> T toDomain(final Class<T> domainClass, final E protobuf) {
		if (protobuf == null) {
			return null;
		}
		T domain = createDomain(domainClass);
		ProtoClass protoClass = testDataBinding(domain.getClass(), protobuf.getClass());
		try {
			fillDomain(domain, protobuf, protoClass);
			return domain;
		} catch (MappingException e) {
			throw new ConverterException("Field mapping error", e);
		} catch (WriteException e) {
			throw new ConverterException("Domain field value setting error", e);
		}
	}

	private ProtoClass testDataBinding(final Class<?> domainClass, final Class<? extends Message> protobufClass) {
		ProtoClass protoClassAnnotation = AnnotationUtils.findProtoClass(domainClass, protobufClass);
		if (protoClassAnnotation == null) {
			throw new ConverterException(new TypeRelationException(domainClass, protobufClass));
		}
		return protoClassAnnotation;
	}

	private <T> T createDomain(final Class<T> domainClass) {
		try {
			return domainClass.newInstance();
		} catch (InstantiationException e) {
			throw new ConverterException("Default constructor not found for " + domainClass.getSimpleName(), e);
		} catch (IllegalAccessException e) {
			throw new ConverterException("Make default constructor of " + domainClass.getSimpleName() + " public", e);
		}
	}

	private <E extends Message> void fillDomain(final Object domain, final E protobuf,
			final ProtoClass protoClassAnnotation) throws MappingException, WriteException {
		Class<?> domainClass = domain.getClass();
		Mapper fieldMapper = AnnotationUtils.createMapper(protoClassAnnotation);
		FieldResolverFactory fieldFactory = AnnotationUtils.createFieldFactory(protoClassAnnotation);
		for (Field field : getDomainFields(domainClass)) {
			if (configuration.getIgnoredFields().ignored(field)) {
				continue;
			}
			FieldResolver fieldResolver = fieldFactory.createResolver(field);
			fillDomainField(fieldResolver, fieldMapper.mapToDomainField(fieldResolver, protobuf, domain));
		}
	}

	private List<Field> getDomainFields(final Class clazz) {
		List<Field> fields = new ArrayList<Field>();

		fields.addAll(Arrays.asList(clazz.getDeclaredFields()));

		if (configuration.withInheritedFields()) {
			Class superClazz = clazz.getSuperclass();
			if (superClazz != null) {
				fields.addAll(getDomainFields(superClazz));
			}
		}
		return fields;
	}

	private void fillDomainField(final FieldResolver fieldResolver, final MappingResult mappingResult)
			throws WriteException {
		DomainWriter fieldWriter = new DomainWriter(mappingResult.getDestination());
		Object mappedValue = mappingResult.getValue();
		switch (mappingResult.getCode()) {
			case NESTED_MAPPING:
				fieldWriter.write(fieldResolver, createNestedConverter().toDomain(fieldResolver.getDomainType(),
						(Message) mappedValue));
				break;
			case MAP_MAPPING:
				ClassTuple mapTypes = FieldUtils.extractMapTypes(fieldResolver.getField());
				mappedValue = createDomainValueMap(mapTypes.v1, mapTypes.v2, mappedValue);
				fieldWriter.write(fieldResolver, mappedValue);
				break;
				
			case COLLECTION_MAPPING:
				Class<?> collectionType = FieldUtils.extractCollectionType(fieldResolver.getField());
				if (FieldUtils.isComplexType(collectionType)) {
					mappedValue = createDomainValueList(collectionType, mappedValue);
				}
			case MAPPED:
			default:
				fieldWriter.write(fieldResolver, mappedValue);
		}
	}

	private Converter createNestedConverter() {
		return create(configuration);
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> createDomainValueList(final Class<T> type, final Object protobufCollection) {
		return createNestedConverter().toDomain(type, (List<? extends Message>) protobufCollection);
	}

	@SuppressWarnings("unchecked")
	private <K, V> Map<K, V> createDomainValueMap(final Class<K> type1, final Class<V> type2, final Object protobufMap) {
		Map<? extends Message, ? extends Message> protoMap = (Map<? extends Message, ? extends Message>) protobufMap;
		Map<K, V> result = new HashMap<K, V>();
		
		for (Map.Entry<? extends Message, ? extends Message> entry: protoMap.entrySet()) {
			K mappedKey = (K) entry.getKey();
			V mappedValue = (V) entry.getValue();

			if (FieldUtils.isComplexType(type1)) {
				mappedKey = createNestedConverter().toDomain(type1, entry.getKey());
			}
			if (FieldUtils.isComplexType(type1)) {
				mappedValue = createNestedConverter().toDomain(type2, entry.getValue());
			}
			result.put(mappedKey, mappedValue);
		}
		return result;
	}

	/**
	 * Create Protobuf dto list from domain object list.
	 *
	 * @param protobufClass    Expected Protobuf class.
	 * @param domainCollection Source domain collection.
	 * @param <T>              Domain type.
	 * @param <E>              Protobuf dto type.
	 * @return Protobuf dto list filled with data stored in the domain object list.
	 */
	@SuppressWarnings("unchecked")
	public <T, E extends Message> List<E> toProtobuf(final Class<E> protobufClass, final Collection<T>
			domainCollection) {
		return toProtobuf(List.class, protobufClass, domainCollection);
	}

	@SuppressWarnings("unchecked")
	private <T, E extends Message, K extends Collection> K toProtobuf(final Class<K> collectionClass,
			final Class<E> protobufClass, final Collection<T> domainCollection) {
		Collection<E> protobufCollection = List.class.isAssignableFrom(collectionClass) ? new ArrayList<E>() : new
				HashSet<E>();
		if (domainCollection != null) {
			for (T domain : domainCollection) {
				protobufCollection.add(toProtobuf(protobufClass, domain));
			}
		}
		return (K) protobufCollection;
	}

	/**
	 * Create Protobuf dto from domain object.
	 *
	 * @param protobufClass Expected Protobuf class.
	 * @param domain        Source domain instance to which protobufClass is bounded.
	 * @param <T>           Domain type.
	 * @param <E>           Protobuf dto type.
	 * @return Protobuf dto filled with data stored in the domain object.
	 */
	@SuppressWarnings("unchecked")
	public <T, E extends Message> E toProtobuf(final Class<E> protobufClass, final T domain) {
		if (domain == null) {
			return null;
		}
		E.Builder protobuf = createProtobuf(protobufClass);
		ProtoClass protoClass = testDataBinding(domain.getClass(), protobufClass);
		try {
			fillProtobuf(protobuf, domain, protoClass);
			return (E) protobuf.build();
		} catch (MappingException e) {
			throw new ConverterException("Field mapping error", e);
		} catch (WriteException e) {
			throw new ConverterException("Protobuf field value setting error", e);
		}
	}

	private <E extends Message> E.Builder createProtobuf(final Class<E> protobufClass) {
		try {
			return (E.Builder) protobufClass.getDeclaredMethod("newBuilder").invoke(null);
		} catch (IllegalAccessException e) {
			throw new ConverterException("Can't access 'newBuilder()' method for " + protobufClass.getName(), e);
		} catch (InvocationTargetException e) {
			throw new ConverterException("Can't instantiate protobuf builder for " + protobufClass.getName(), e);
		} catch (NoSuchMethodException e) {
			throw new ConverterException("Method 'newBuilder()' not found in " + protobufClass.getName(), e);
		}
	}

	private <E extends Message.Builder> void fillProtobuf(final E protobuf, final Object domain,
			final ProtoClass protoClassAnnotation) throws MappingException, WriteException {
		Class<?> domainClass = domain.getClass();
		Mapper fieldMapper = AnnotationUtils.createMapper(protoClassAnnotation);
		FieldResolverFactory fieldFactory = AnnotationUtils.createFieldFactory(protoClassAnnotation);
		for (Field field : getDomainFields(domainClass)) {
			if (configuration.getIgnoredFields().ignored(field)) {
				continue;
			}
			FieldResolver fieldResolver = fieldFactory.createResolver(field);
			fillProtobufField(fieldResolver, fieldMapper.mapToProtobufField(fieldResolver, domain, protobuf));
		}
	}

	private void fillProtobufField(final FieldResolver fieldResolver, final MappingResult mappingResult)
			throws WriteException {
		ProtobufWriter fieldWriter = new ProtobufWriter((Message.Builder) mappingResult.getDestination());
		Object mappedValue = mappingResult.getValue();
		switch (mappingResult.getCode()) {
			case NESTED_MAPPING:
				Class<? extends Message> protobufClass = MessageUtils.getMessageType(mappingResult.getDestination(),
						FieldUtils.createProtobufGetterName(fieldResolver));
				fieldWriter.write(fieldResolver, createNestedConverter().toProtobuf(protobufClass, mappedValue));
				break;
			case MAP_MAPPING:
				ClassTuple domainMapTypes = FieldUtils.extractMapTypes(fieldResolver.getField());
				MessageClassTuple protoMapTypes = MessageUtils.getMessageMapTypes(
						mappingResult.getDestination(), FieldUtils.createProtobufGetterName(fieldResolver));
				mappedValue = createProtobufMap(domainMapTypes.v1, domainMapTypes.v2, protoMapTypes.v1, protoMapTypes.v2, (Map) mappedValue);
				fieldWriter.write(fieldResolver, mappedValue);
				break;
			case COLLECTION_MAPPING:
				Class<?> collectionType = FieldUtils.extractCollectionType(fieldResolver.getField());
				if (FieldUtils.isComplexType(collectionType)) {
					Class<? extends Message> protobufCollectionClass = MessageUtils.getMessageCollectionType(
							mappingResult.getDestination(), FieldUtils.createProtobufGetterName(fieldResolver));
					mappedValue = createProtobufValueList(protobufCollectionClass, fieldResolver.getDomainType(),
							(Collection) mappedValue);
				}
			case MAPPED:
			default:
				fieldWriter.write(fieldResolver, mappedValue);
		}
	}


	private <E extends Message> Collection<?> createProtobufValueList(final Class<E> type, final Class<?>
			domainCollectionClass, final Collection<?> domainCollection) {
		return createNestedConverter()
				.toProtobuf((Class<? extends Collection>) domainCollectionClass, type, domainCollection);
	}

	@SuppressWarnings("unchecked")
	private <P, Q, R, S> Map<R, S> createProtobufMap(final Class<P> domainKeyClass, final Class<Q> domainValueClass, 
			            final Class<R> protoKeyClass, final Class<S> protoValueClass, 
			            final Map<?,?> domainMap) {
		Map<R, S> result = new HashMap<>();
		
		if (domainMap == null) {
			return null;
		}
		
		for (Entry<?, ?> entry: domainMap.entrySet()) {
			R mappedKey = null; 
			S mappedValue = null;
			
			if (protoKeyClass.isAssignableFrom(entry.getValue().getClass())) {
				mappedKey = (R) entry.getKey();
			}
			if (protoValueClass.isAssignableFrom(entry.getValue().getClass())) {
				mappedValue = (S) entry.getValue();
			}

			if (FieldUtils.isComplexType(protoKeyClass)) {
				mappedKey = (R) createNestedConverter().toProtobuf((Class<? extends Message>)protoKeyClass, entry.getKey());
			}
			if (FieldUtils.isComplexType(protoValueClass)) {
				mappedValue = (S) createNestedConverter().toProtobuf((Class<? extends Message>)protoValueClass, entry.getValue());
			}
			
			if (mappedKey == null || mappedValue == null) {
				// ignore this for now
			} else {
				result.put(mappedKey, mappedValue);
			}
		}
		
		return result;
	}

}
