package leekscript.compiler;

import leekscript.common.Annotation;

public interface Annotatable {
	void addAnnotation(Annotation a);

	/**
	 * Variante avec un argument (ex: {@code @deprecated("use x instead")}).
	 * Par défaut on ignore la raison ; les cibles qui savent l'exploiter
	 * (variables, fonctions, méthodes) surchargent cette méthode.
	 */
	default void addAnnotation(Annotation a, String reason) {
		addAnnotation(a);
	}
}
