package org.demiurg906.kotlin.plugin

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.demiurg906.kotlin.plugin.fir.SimpleClassGenerator
import org.demiurg906.kotlin.plugin.fir.SimpleUsageAnalysis

class SimplePluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::SimpleClassGenerator
        +::SimpleUsageAnalysis
    }
}
