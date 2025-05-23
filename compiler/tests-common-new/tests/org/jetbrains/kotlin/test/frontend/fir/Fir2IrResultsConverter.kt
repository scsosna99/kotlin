/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.platform.isWasm
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.model.BackendKinds
import org.jetbrains.kotlin.test.model.Frontend2BackendConverter
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.ServiceRegistrationData
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.targetPlatform

@RequiresOptIn("Please use common converter `Fir2IrResultsConverter` instead")
annotation class InternalFir2IrConverterAPI

@OptIn(InternalFir2IrConverterAPI::class)
class Fir2IrResultsConverter(
    testServices: TestServices
) : Frontend2BackendConverter<FirOutputArtifact, IrBackendInput>(
    testServices,
    FrontendKinds.FIR,
    BackendKinds.IrBackend
) {
    private val jvmResultsConverter = Fir2IrJvmResultsConverter(testServices)
    private val jsResultsConverter = Fir2IrJsResultsConverter(testServices)
    private val wasmResultsConverter = Fir2IrWasmResultsConverter(testServices)

    override val additionalServices: List<ServiceRegistrationData>
        get() = jvmResultsConverter.additionalServices + jsResultsConverter.additionalServices + wasmResultsConverter.additionalServices

    override val directiveContainers: List<DirectivesContainer>
        get() = jvmResultsConverter.directiveContainers + jsResultsConverter.directiveContainers + wasmResultsConverter.directiveContainers

    override fun transform(module: TestModule, inputArtifact: FirOutputArtifact): IrBackendInput? {
        val targetPlatform = module.targetPlatform(testServices)
        return when {
            targetPlatform.isJvm() || targetPlatform.isCommon() -> {
                jvmResultsConverter.transform(module, inputArtifact)
            }
            targetPlatform.isJs() -> {
                jsResultsConverter.transform(module, inputArtifact)
            }
            targetPlatform.isWasm() -> {
                wasmResultsConverter.transform(module, inputArtifact)
            }
            else -> error("Unsupported platform: $targetPlatform")
        }
    }
}
