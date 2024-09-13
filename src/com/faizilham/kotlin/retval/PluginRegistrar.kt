package com.faizilham.kotlin.retval

import com.faizilham.kotlin.retval.fir.UsageFlowAnalysisExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class PluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
//        +::UsageAnalysisExtension
//        +::UsageObligationAttributeExtension
        +::UsageFlowAnalysisExtension
    }
}
