/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.intellij.plugins.hil

import com.intellij.psi.InjectedLanguagePlaces
import com.intellij.psi.LanguageInjector
import com.intellij.psi.PsiLanguageInjectionHost
import org.intellij.plugins.hcl.psi.impl.JavaUtil
import org.intellij.plugins.hcl.psi.isInHCLFileWithInterpolations
import org.intellij.plugins.hcl.terraform.config.TerraformFileType
import org.intellij.plugins.hil.psi.ILStringLiteralExpression
import org.intellij.plugins.hil.psi.impl.getHCLHost

class ILinILLanguageInjector : LanguageInjector {
  override fun getLanguagesToInject(host: PsiLanguageInjectionHost, places: InjectedLanguagePlaces) {
    return Companion.getLanguagesToInject(host, places)
  }

  companion object {
    fun getLanguagesToInject(host: PsiLanguageInjectionHost, places: InjectedLanguagePlaces) {
      if (host !is ILStringLiteralExpression) return
      if (!host.isInHCLFileWithInterpolations()) return
      val hcl = host.getHCLHost() ?: return
      val file = (hcl as ILStringLiteralExpression).containingFile
      // Only .tf (Terraform config) files
      // Restrict interpolations in .tfvars files // TODO: This file shouldn't know about .tfvars here
      if (file.fileType == TerraformFileType && file.name.endsWith("." + TerraformFileType.TFVARS_EXTENSION)) return
      if (host is ILStringLiteralExpression) return getStringLiteralInjections(host, places)
      return
    }

    fun getStringLiteralInjections(host: ILStringLiteralExpression, places: InjectedLanguagePlaces) {
      if (!host.text.contains("\${")) return
      val fragments = JavaUtil.doGetTextFragments(host.text, true, true)
      for (pair in fragments) {
        val fragment = pair.second
        if (!fragment.startsWith("\${")) continue
        val ranges = ILLanguageInjector.getILRangesInText(fragment)
        for (range in ranges) {
          val rng = range.shiftRight(pair.first.startOffset)
          places.addPlace(HILLanguage, rng, null, null)
        }
      }
    }

  }
}

