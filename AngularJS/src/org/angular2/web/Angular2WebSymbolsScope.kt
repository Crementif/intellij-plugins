// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.web

import com.intellij.javascript.web.symbols.*
import com.intellij.javascript.web.symbols.WebSymbolsContainer.Namespace.JS
import com.intellij.model.Pointer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.refactoring.suggested.createSmartPointer
import com.intellij.util.castSafelyTo
import org.angular2.Angular2Framework
import org.angular2.codeInsight.Angular2CodeInsightUtils
import org.angular2.codeInsight.Angular2CodeInsightUtils.wrapWithImportDeclarationModuleHandler
import org.angular2.codeInsight.Angular2DeclarationsScope
import org.angular2.codeInsight.Angular2DeclarationsScope.DeclarationProximity
import org.angular2.entities.Angular2Directive
import org.angular2.lang.expr.psi.Angular2TemplateBindings
import org.angular2.lang.html.psi.Angular2HtmlTemplateBindings
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_ATTRIBUTES
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_ATTRIBUTE_SELECTORS
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_ELEMENT_SELECTORS
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_INPUTS
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_IN_OUTS
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_ONE_TIME_BINDINGS
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_DIRECTIVE_OUTPUTS
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.KIND_NG_STRUCTURAL_DIRECTIVES
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.PROP_ERROR_SYMBOL
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.PROP_SCOPE_PROXIMITY
import org.angular2.web.Angular2WebSymbolsAdditionalContextProvider.Companion.PROP_SYMBOL_DIRECTIVE
import java.util.*

class Angular2WebSymbolsScope(private val context: PsiFile) : WebSymbolsScope {

  private val scope = Angular2DeclarationsScope(context)

  override fun apply(matches: List<WebSymbol>,
                     strict: Boolean,
                     namespace: WebSymbolsContainer.Namespace?,
                     kind: SymbolKind,
                     name: String?): List<WebSymbol> =
    if (namespace == JS && kinds.contains(kind)) {
      if (strict) {
        matches.filter { symbol ->
          symbol.properties[PROP_SYMBOL_DIRECTIVE].castSafelyTo<Angular2Directive>()?.let { scope.contains(it) } != false
          && symbol.properties[PROP_ERROR_SYMBOL] != true
        }
      }
      else {
        val byName = if (name == null)
          matches.groupBy { it.matchedName }
        else
          mapOf(Pair(name, matches))

        byName.flatMap { (_, list) ->
          val proximityMap = list.groupBy {
            val directive = it.properties[PROP_SYMBOL_DIRECTIVE] as? Angular2Directive
            if (directive != null)
              scope.getDeclarationProximity(directive)
            else if (it.properties[PROP_ERROR_SYMBOL] == true)
              DeclarationProximity.NOT_REACHABLE
            else
              DeclarationProximity.IN_SCOPE
          }
          DeclarationProximity.values().firstNotNullOfOrNull { proximity ->
            proximityMap[proximity]?.takeIf { it.isNotEmpty() }?.map { Angular2ScopedSymbol(it, proximity) }
          }
          ?: emptyList()
        }
      }
    }
    else matches

  override fun apply(item: WebSymbolCodeCompletionItem,
                     namespace: WebSymbolsContainer.Namespace?,
                     kind: SymbolKind): WebSymbolCodeCompletionItem? {
    val symbol = item.symbol
    if (symbol == null || namespace != JS || !kinds.contains(kind)) return item
    val directives = symbol.unwrapMatchedSymbols()
      .mapNotNull { it.properties[PROP_SYMBOL_DIRECTIVE]?.castSafelyTo<Angular2Directive>() }
      .toList()
    return if (symbol.properties[PROP_ERROR_SYMBOL] == true) {
      null
    }
    else if (directives.isNotEmpty()) {
      val proximity = scope.getDeclarationsProximity(directives)
      if (proximity == DeclarationProximity.NOT_REACHABLE) {
        null
      }
      else {
        wrapWithImportDeclarationModuleHandler(
          Angular2CodeInsightUtils.decorateCodeCompletionItem(item, directives, proximity, scope),
          when (kind) {
            KIND_NG_DIRECTIVE_ELEMENT_SELECTORS -> XmlTag::class.java
            KIND_NG_STRUCTURAL_DIRECTIVES -> Angular2TemplateBindings::class.java
            else -> XmlAttribute::class.java
          })
      }
    }
    else item
  }

  companion object {
    private val kinds = setOf(KIND_NG_STRUCTURAL_DIRECTIVES,
                              KIND_NG_DIRECTIVE_ONE_TIME_BINDINGS, KIND_NG_DIRECTIVE_ATTRIBUTES,
                              KIND_NG_DIRECTIVE_INPUTS, KIND_NG_DIRECTIVE_OUTPUTS, KIND_NG_DIRECTIVE_IN_OUTS,
                              KIND_NG_DIRECTIVE_ELEMENT_SELECTORS, KIND_NG_DIRECTIVE_ATTRIBUTE_SELECTORS)
  }

  override fun createPointer(): Pointer<out WebSymbolsScope> {
    val contextPtr = context.createSmartPointer()
    return Pointer {
      contextPtr.dereference()?.let { Angular2WebSymbolsScope(it) }
    }
  }

  override fun equals(other: Any?): Boolean =
    other === this ||
    other is Angular2WebSymbolsScope
    && other.context == context

  override fun hashCode(): Int =
    context.hashCode()

  override fun getModificationCount(): Long =
    context.project.psiModificationCount

  class Provider : WebSymbolsScopeProvider {
    override fun get(context: PsiElement, framework: FrameworkId?): WebSymbolsScope? =
      framework
        ?.takeIf { it == Angular2Framework.ID }
        ?.let { Angular2WebSymbolsScope(context.containingFile) }

  }

  private class Angular2ScopedSymbol(symbol: WebSymbol,
                                     private val scopeProximity: DeclarationProximity) : WebSymbolDelegate<WebSymbol>(symbol) {

    override val priority: WebSymbol.Priority?
      get() = if (scopeProximity == DeclarationProximity.IN_SCOPE || scopeProximity == DeclarationProximity.EXPORTED_BY_PUBLIC_MODULE)
        super.priority
      else
        WebSymbol.Priority.LOWEST

    override fun createPointer(): Pointer<Angular2ScopedSymbol> {
      val delegatePtr = delegate.createPointer()
      val scopeProximity = this.scopeProximity
      return Pointer {
        delegatePtr.dereference()?.let { Angular2ScopedSymbol(it, scopeProximity) }
      }
    }

    override fun equals(other: Any?): Boolean =
      other === this
      || other is Angular2ScopedSymbol
      && other.delegate == delegate
      && other.scopeProximity == scopeProximity

    override fun hashCode(): Int =
      Objects.hash(delegate, scopeProximity)

    override val properties: Map<String, Any>
      get() = super.properties + Pair(PROP_SCOPE_PROXIMITY, scopeProximity)

  }
}