// This is a generated file. Not intended for manual editing.
package name.kropp.intellij.makefile.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface MakefileRule extends PsiElement {

  @NotNull
  MakefileRecipe getRecipe();

  @NotNull
  MakefileTargetLine getTargetLine();

  List<MakefileTarget> getTargets();

}