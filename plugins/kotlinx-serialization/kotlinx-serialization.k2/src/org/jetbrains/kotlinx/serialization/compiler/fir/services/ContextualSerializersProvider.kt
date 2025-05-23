/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.fir.services

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.createCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.caches.getValue
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getTargetType
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlinx.serialization.compiler.fir.*
import org.jetbrains.kotlinx.serialization.compiler.fir.checkers.classSerializer
import org.jetbrains.kotlinx.serialization.compiler.fir.checkers.currentFile
import org.jetbrains.kotlinx.serialization.compiler.fir.checkers.getOverriddenSerializer
import org.jetbrains.kotlinx.serialization.compiler.resolve.*


class ContextualSerializersProvider(session: FirSession) : FirExtensionSessionComponent(session) {
    private val contextualKClassListCache: FirCache<FirFileSymbol, Set<ConeKotlinType>, Nothing?> =
        session.firCachesFactory.createCache { fileSymbol ->
            buildSet {
                addAll(getKClassListFromFileAnnotation(fileSymbol, SerializationAnnotations.contextualClassId))
                addAll(getKClassListFromFileAnnotation(fileSymbol, SerializationAnnotations.contextualOnFileClassId))
            }
        }

    fun getContextualKClassListForFile(fileSymbol: FirFileSymbol): Set<ConeKotlinType> {
        return contextualKClassListCache.getValue(fileSymbol)
    }

    private val additionalSerializersInScopeCache: FirCache<FirFileSymbol, Map<Pair<FirClassSymbol<*>, Boolean>, FirClassSymbol<*>>, Nothing?> =
        session.firCachesFactory.createCache { file ->
            getKClassListFromFileAnnotation(file, SerializationAnnotations.additionalSerializersClassId).associateBy(
                keySelector = {
                    val serializerType = it.serializerForType(session)
                    val symbol = serializerType?.toRegularClassSymbol(session)
                        ?: throw AssertionError("Argument for ${SerializationAnnotations.additionalSerializersFqName} does not implement KSerializer or does not provide serializer for concrete type")
                    symbol to serializerType.isMarkedNullable
                },
                valueTransform = { it.toRegularClassSymbol(session)!! }
            )
        }

    fun getAdditionalSerializersInScopeForFile(file: FirFileSymbol): Map<Pair<FirClassSymbol<*>, Boolean>, FirClassSymbol<*>> {
        return additionalSerializersInScopeCache.getValue(file)
    }

    private fun FirExpression.unwrapArguments(): List<FirExpression> = when (this) {
        is FirArrayLiteral -> arguments
        is FirVarargArgumentsExpression -> arguments
        else -> emptyList()
    }

    private fun getKClassListFromFileAnnotation(file: FirFileSymbol, annotationClassId: ClassId): List<ConeKotlinType> {
        val annotation = file.resolvedAnnotationsWithArguments.getAnnotationByClassId(
            annotationClassId, session
        ) ?: return emptyList()
        val annotationArgument = annotation.argumentMapping.mapping.values.firstOrNull()
        val arguments = annotationArgument?.unwrapArguments() ?: return emptyList()
        val classes: List<FirGetClassCall> = arguments.flatMap {
            when (it) {
                is FirGetClassCall -> listOf(it)
                is FirSpreadArgumentExpression -> it.expression.unwrapArguments().filterIsInstance<FirGetClassCall>()
                else -> emptyList()
            }
        }
        return classes.mapNotNull { it.getTargetType()?.fullyExpandedType(session) }
    }
}

val FirSession.contextualSerializersProvider: ContextualSerializersProvider by FirSession.sessionComponentAccessor()

fun findTypeSerializerOrContextUnchecked(type: ConeKotlinType, c: CheckerContext): FirClassSymbol<*>? {
    if (type.isTypeParameter) return null
    val session = c.session
    val annotations = type.fullyExpandedType(session).customAnnotations
    annotations.getSerializableWith(session)?.let { return it.classSymbolOrUpperBound(session) }
    val classSymbol = type.classSymbolOrUpperBound(session) ?: return null
    val currentFile = c.currentFile
    val provider = session.contextualSerializersProvider
    provider.getAdditionalSerializersInScopeForFile(currentFile)[classSymbol to type.isMarkedNullable]?.let { return it }
    if (type.isMarkedNullable) {
        return findTypeSerializerOrContextUnchecked(type.withNullability(nullable = false, session.typeContext), c)
    }
    if (type in provider.getContextualKClassListForFile(currentFile)) {
        return session.dependencySerializationInfoProvider.getClassFromSerializationPackage(SpecialBuiltins.Names.contextSerializer)
    }
    return analyzeSpecialSerializers(session, annotations) ?: findTypeSerializer(type.upperBoundIfFlexible(), c)
}

/**
 * Returns class descriptor for ContextSerializer or PolymorphicSerializer
 * if [annotations] contains @Contextual or @Polymorphic annotation
 */
fun analyzeSpecialSerializers(session: FirSession, annotations: List<FirAnnotation>): FirClassSymbol<*>? = when {
    annotations.hasAnnotation(SerializationAnnotations.contextualClassId, session) ||
            annotations.hasAnnotation(SerializationAnnotations.contextualOnPropertyClassId, session) -> {
        session.dependencySerializationInfoProvider.getClassFromSerializationPackage(SpecialBuiltins.Names.contextSerializer)
    }
    // can be annotation on type usage, e.g. List<@Polymorphic Any>
    annotations.hasAnnotation(SerializationAnnotations.polymorphicClassId, session) -> {
        session.dependencySerializationInfoProvider.getClassFromSerializationPackage(SpecialBuiltins.Names.polymorphicSerializer)
    }

    else -> null
}

fun findTypeSerializer(type: ConeKotlinType, c: CheckerContext): FirClassSymbol<*>? {
    val session = c.session
    val userOverride = type.getOverriddenSerializer(session)
    if (userOverride != null) return userOverride.classSymbolOrUpperBound(session)
    if (type.isTypeParameter) return null
    val serializationProvider = session.dependencySerializationInfoProvider
    if (type.isArrayType) {
        return serializationProvider.getClassFromInternalSerializationPackage(SpecialBuiltins.Names.referenceArraySerializer)
    }
    if (with(session) { type.isGeneratedSerializableObject(session) }) {
        return serializationProvider.getClassFromInternalSerializationPackage(SpecialBuiltins.Names.objectSerializer)
    }
    // see if there is a standard serializer
    val standardSerializer = with(session) { findStandardKotlinTypeSerializer(type, session) ?: findEnumTypeSerializer(type, session) }
    if (standardSerializer != null) return standardSerializer
    val symbol = type.toRegularClassSymbol(session) ?: return null
    if (symbol.isSealedSerializableInterface(session)) {
        return serializationProvider.getClassFromSerializationPackage(SpecialBuiltins.Names.polymorphicSerializer)
    }
    return symbol.classSerializer(c) // check for serializer defined on the type
}

fun findStandardKotlinTypeSerializer(type: ConeKotlinType, session: FirSession): FirClassSymbol<*>? {
    val name = when {
        type.isBoolean -> PrimitiveBuiltins.booleanSerializer
        type.isByte -> PrimitiveBuiltins.byteSerializer
        type.isShort -> PrimitiveBuiltins.shortSerializer
        type.isInt -> PrimitiveBuiltins.intSerializer
        type.isLong -> PrimitiveBuiltins.longSerializer
        type.isFloat -> PrimitiveBuiltins.floatSerializer
        type.isDouble -> PrimitiveBuiltins.doubleSerializer
        type.isChar -> PrimitiveBuiltins.charSerializer
        else -> findStandardKotlinTypeSerializerName(type.classId?.asFqNameString())
    }?.let(Name::identifier) ?: return null
    val symbolProvider = session.symbolProvider
    return symbolProvider.getClassLikeSymbolByClassId(ClassId(SerializationPackages.internalPackageFqName, name)) as? FirClassSymbol<*>
        ?: symbolProvider.getClassLikeSymbolByClassId(ClassId(SerializationPackages.packageFqName, name)) as? FirClassSymbol<*>
}

fun findEnumTypeSerializer(type: ConeKotlinType, session: FirSession): FirClassSymbol<*>? {
    val symbol = type.toRegularClassSymbol(session) ?: return null
    return runIf(symbol.isEnumClass && !symbol.isEnumWithLegacyGeneratedSerializer(session)) {
        session.symbolProvider.getClassLikeSymbolByClassId(SerializersClassIds.enumSerializerId) as? FirClassSymbol<*>
    }
}
