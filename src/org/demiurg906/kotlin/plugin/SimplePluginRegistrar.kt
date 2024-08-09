package org.demiurg906.kotlin.plugin

import org.demiurg906.kotlin.plugin.fir.SimpleUsageAnalysis
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class SimplePluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::SimpleUsageAnalysis
    }
}
