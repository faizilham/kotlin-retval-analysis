package com.faizilham.kotlin.retval

import com.faizilham.kotlin.retval.fir.UsageAnalysisExtension
import com.faizilham.kotlin.retval.fir.UsageObligationAttributeExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class PluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::UsageAnalysisExtension
        +::UsageObligationAttributeExtension
    }
}
