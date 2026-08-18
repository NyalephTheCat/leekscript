package leekscript.compiler;

import leekscript.runner.Session;

/**
 * @param profile émet l'instrumentation de profilage (frames enter/exit autour de chaque
 *                fonction, table de libellés dans la classe générée). Le code produit diffère
 *                donc du code de production : la signature de cache en tient compte, cf
 *                {@link JavaCompiler#compile}.
 */
public record Options(int version, boolean strict, boolean useCache, boolean enableOperations, Session session, boolean useExtra, boolean profile) {

	public Options(int version, boolean strict, boolean useCache, boolean enableOperations, Session session, boolean useExtra) {
		this(version, strict, useCache, enableOperations, session, useExtra, false);
	}
	public Options() {
		this(LeekScript.LATEST_VERSION, false, false, false, null, true);
	}
	public Options(boolean operations) {
		this(LeekScript.LATEST_VERSION, false, false, operations, null, true);
	}
	public Options(Session session) {
		this(LeekScript.LATEST_VERSION, false, true, true, session, true);
	}
}
