package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.attributes.ConeUsageObligationAttribute
import com.faizilham.kotlin.retval.fir.checkers.commons.Commons
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.impl.FirEmptyAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirTypeAttributeExtension
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl

/*
 * TypeAttributeExtension for UsageObligation, based on:
 *    https://github.com/JetBrains/kotlin/blob/master/plugins/fir-plugin-prototype/src/org/jetbrains/kotlin/fir/plugin/types/FirNumberSignAttributeExtension.kt
*/

class UsageObligationAttributeExtension(session: FirSession) : FirTypeAttributeExtension(session) {
    override fun convertAttributeToAnnotation(attribute: ConeAttribute<*>): FirAnnotation? {
        if (attribute !is ConeUsageObligationAttribute) return null
        val classId = when (attribute.usage) {
            ConeUsageObligationAttribute.UsageObligation.MayUse -> Commons.Annotations.MayUse
            ConeUsageObligationAttribute.UsageObligation.AnyUse -> Commons.Annotations.AnyUse
        }
        return buildAnnotation {
            annotationTypeRef = buildResolvedTypeRef {
                type = ConeClassLikeTypeImpl(
                    classId.toLookupTag(),
                    ConeTypeProjection.EMPTY_ARRAY,
                    isNullable = false
                )
            }
            argumentMapping = FirEmptyAnnotationArgumentMapping
        }
    }

    override fun extractAttributeFromAnnotation(annotation: FirAnnotation): ConeAttribute<*>? {
        val usage = when (annotation.annotationTypeRef.coneTypeOrNull?.classId) {
            Commons.Annotations.MayUse -> ConeUsageObligationAttribute.UsageObligation.MayUse
            Commons.Annotations.AnyUse -> ConeUsageObligationAttribute.UsageObligation.AnyUse
            else -> return null
        }
        return ConeUsageObligationAttribute.fromUsageObligation(usage)
    }
}