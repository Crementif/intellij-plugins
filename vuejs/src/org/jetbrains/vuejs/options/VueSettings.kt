// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.options

import com.intellij.lang.typescript.lsp.restartTypeScriptServicesAsync
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

fun getVueSettings(project: Project): VueSettings = project.service<VueSettings>() //

@State(name = "VueSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VueSettings(val project: Project) : SimplePersistentStateComponent<VueSettingsState>(VueSettingsState()) {
  var serviceType
    get() = state.innerServiceType
    set(value) {
      val changed = state.innerServiceType != value
      state.innerServiceType = value
      if (changed) restartTypeScriptServicesAsync(project)
    }
}

class VueSettingsState : BaseState() {
  var innerServiceType by enum(VueServiceSettings.AUTO)
}

enum class VueServiceSettings {
  AUTO,
  VOLAR,
  TS_SERVICE,
  DISABLED
}