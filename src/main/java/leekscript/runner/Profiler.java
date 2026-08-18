package leekscript.runner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profileur d'IA pondéré par le compteur d'opérations.
 *
 * <p>Accumule un <i>calling context tree</i> : un nœud par chemin d'appel distinct, ce qui est
 * exactement la structure d'un flamegraph. Le poids d'une barre est le coût <b>propre</b> (self)
 * en opérations.
 *
 * <p>Point clé : {@link AI#ops(int)} n'est pas touché. Le coût est reconstruit en ne lisant
 * {@code mOperations} qu'aux <b>entrées et sorties de fonction</b> :
 *
 * <pre>
 * enter : entryOps[d] = ops ; childOps[d] = 0
 * exit  : incl = ops - entryOps[d]
 *         node.inclusiveOps += incl
 *         node.selfOps      += incl - childOps[d]
 *         childOps[d-1]     += incl
 * </pre>
 *
 * Le compteur n'est ni augmenté ni diminué : un combat profilé facture exactement comme un
 * combat normal.
 *
 * <p>L'arbre survit aux tours : seule la pile de profondeur est remise à zéro par
 * {@link #resetStack()} quand le moteur remet le compteur à zéro. C'est ce qui donne un profil
 * sur un combat entier et non sur un tour.
 *
 * <p>Non thread-safe : un profileur appartient à une IA, et une IA est exécutée par un seul
 * thread (un engine = un combat = un thread).
 */
public class Profiler {

	/** Au-delà, on cesse d'ouvrir de nouveaux contextes (IA pathologique à récursion large). */
	public static final int DEFAULT_MAX_NODES = 200_000;
	/** Au-delà, les frames ne sont plus suivies (leur coût retombe sur l'ancêtre survivant). */
	public static final int DEFAULT_MAX_DEPTH = 4_096;

	public static final class Node {
		final int frameId;
		final Node parent;
		private Map<Integer, Node> children;
		long calls;
		long selfOps;
		long inclusiveOps;

		Node(int frameId, Node parent) {
			this.frameId = frameId;
			this.parent = parent;
		}

		public int getFrameId() { return frameId; }
		public long getCalls() { return calls; }
		public long getSelfOps() { return selfOps; }
		public long getInclusiveOps() { return inclusiveOps; }
		public Iterable<Node> getChildren() {
			return children == null ? List.of() : children.values();
		}
	}

	private final int maxNodes;
	private final int maxDepth;

	private final Node root = new Node(-1, null);
	private int nodeCount = 1;

	/** Table des libellés : indices 0..staticCount-1 = table émise par le compilateur. */
	private final List<String> labels = new ArrayList<>();
	private final Map<String, Integer> dynamicLabels = new HashMap<>();

	private Node[] stack = new Node[64];
	private long[] entryOps = new long[64];
	private long[] childOps = new long[64];
	private int depth = 0;

	/**
	 * Frames entrées mais non suivies (plafond de nœuds ou de profondeur atteint). Toujours au
	 * sommet de la pile, donc un simple compteur suffit à conserver l'appariement LIFO.
	 */
	private int skipped = 0;

	private boolean truncated = false;

	/** Somme courante des coûts propres, tenue à jour en O(1) pour la série par tour. */
	private long totalSelfOps = 0;

	public Profiler() {
		this(DEFAULT_MAX_NODES, DEFAULT_MAX_DEPTH);
	}

	public Profiler(int maxNodes, int maxDepth) {
		this.maxNodes = maxNodes;
		this.maxDepth = maxDepth;
	}

	/** Installe la table de libellés émise par le compilateur dans la classe de l'IA. */
	public void setStaticFrames(String[] staticFrames) {
		if (staticFrames == null || !labels.isEmpty()) return;
		for (String label : staticFrames) {
			labels.add(label);
		}
	}

	/**
	 * Enregistre un libellé posé par le moteur (racines {@code runIA}, {@code staticInit},
	 * hooks…), qui n'existe pas dans la table du compilateur.
	 */
	public int internFrame(String label) {
		Integer existing = dynamicLabels.get(label);
		if (existing != null) return existing;
		int id = labels.size();
		labels.add(label);
		dynamicLabels.put(label, id);
		return id;
	}

	public String getLabel(int frameId) {
		return frameId >= 0 && frameId < labels.size() ? labels.get(frameId) : "#" + frameId;
	}

	public void enter(int frameId, long ops) {
		if (skipped > 0 || depth >= maxDepth) {
			skipped++;
			truncated = true;
			return;
		}
		Node parent = depth == 0 ? root : stack[depth - 1];
		Node node = child(parent, frameId);
		if (node == null) {
			skipped++;
			truncated = true;
			return;
		}
		if (depth == stack.length) grow();
		stack[depth] = node;
		entryOps[depth] = ops;
		childOps[depth] = 0;
		node.calls++;
		depth++;
	}

	public void exit(long ops) {
		if (skipped > 0) {
			skipped--;
			return;
		}
		if (depth == 0) return; // désynchronisation : on ne facture rien plutôt que de fausser
		depth--;
		Node node = stack[depth];
		long inclusive = ops - entryOps[depth];
		node.inclusiveOps += inclusive;
		long self = inclusive - childOps[depth];
		node.selfOps += self;
		totalSelfOps += self;
		if (depth > 0) childOps[depth - 1] += inclusive;
		stack[depth] = null;
	}

	/**
	 * Vide la pile de profondeur sans toucher à l'arbre. Appelé aux frontières où le moteur
	 * remet {@code mOperations} à zéro (début de tour, hook) : les deltas calculés à cheval sur
	 * une remise à zéro seraient négatifs.
	 */
	public void resetStack() {
		while (depth > 0) {
			depth--;
			stack[depth] = null;
		}
		skipped = 0;
	}

	/** Un contexte ou une profondeur a été abandonné : le profil est incomplet. */
	public boolean isTruncated() { return truncated; }

	public int getNodeCount() { return nodeCount; }

	public Node getRoot() { return root; }

	/** Somme des coûts propres, en O(1). Doit valoir le total d'opérations facturé. */
	public long getTotalSelfOps() {
		return totalSelfOps;
	}

	/** Même valeur, recalculée en parcourant l'arbre — sert à vérifier l'invariant en test. */
	public long computeTotalSelfOps() {
		return sumSelf(root);
	}

	private long sumSelf(Node node) {
		long total = node.selfOps;
		for (Node child : node.getChildren()) {
			total += sumSelf(child);
		}
		return total;
	}

	/**
	 * Écrit l'arbre au format <i>folded stacks</i> — une ligne par chemin d'appel, libellés
	 * séparés par {@code ;}, poids = opérations propres. Les chemins de poids nul sont omis.
	 *
	 * @param prefix préfixe de racine (typiquement l'entité), ou {@code null}
	 */
	public void writeFolded(Appendable out, String prefix) throws IOException {
		writeFolded(out, prefix, null);
	}

	/**
	 * Même chose, restreinte aux tours racines dont le libellé passe {@code acceptRoot}.
	 *
	 * <p>C'est ce qui permet un fichier PAR ENTITÉ : une invocation exécute la fonction d'IA de
	 * son invocateur sur l'objet AI de celui-ci, donc son profil vit dans l'arbre de
	 * l'invocateur — mais sous une racine à son propre nom, qu'on peut isoler ici.
	 *
	 * @param acceptRoot filtre sur le libellé des racines, ou {@code null} pour tout écrire
	 */
	public void writeFolded(Appendable out, String prefix, java.util.function.Predicate<String> acceptRoot) throws IOException {
		StringBuilder path = new StringBuilder();
		if (prefix != null && !prefix.isEmpty()) {
			path.append(sanitize(prefix));
		}
		for (Node child : root.getChildren()) {
			if (acceptRoot != null && !acceptRoot.test(getLabel(child.frameId))) continue;
			writeFolded(out, child, path);
		}
	}

	/** Libellés des tours racines de l'arbre (une par entité, plus les hooks). */
	public java.util.List<String> getRootLabels() {
		var labels = new ArrayList<String>();
		for (Node child : root.getChildren()) {
			labels.add(getLabel(child.frameId));
		}
		return labels;
	}

	/** Somme des coûts propres des tours racines acceptées par le filtre. */
	public long getSelfOps(java.util.function.Predicate<String> acceptRoot) {
		long total = 0;
		for (Node child : root.getChildren()) {
			if (acceptRoot != null && !acceptRoot.test(getLabel(child.frameId))) continue;
			total += sumSelf(child);
		}
		return total;
	}

	private void writeFolded(Appendable out, Node node, StringBuilder path) throws IOException {
		int mark = path.length();
		if (node != root) {
			if (mark > 0) path.append(';');
			path.append(sanitize(getLabel(node.frameId)));
			if (node.selfOps > 0) {
				out.append(path).append(' ').append(Long.toString(node.selfOps)).append('\n');
			}
		}
		for (Node child : node.getChildren()) {
			writeFolded(out, child, path);
		}
		path.setLength(mark);
	}

	/** {@code ;} et retour à la ligne sont les séparateurs du format folded. */
	private static String sanitize(String label) {
		return label.replace(';', ',').replace('\n', ' ').replace('\r', ' ').replace(' ', '_');
	}

	private Node child(Node parent, int frameId) {
		if (parent.children == null) {
			if (nodeCount >= maxNodes) return null;
			parent.children = new HashMap<>(4);
		}
		Node node = parent.children.get(frameId);
		if (node == null) {
			if (nodeCount >= maxNodes) return null;
			node = new Node(frameId, parent);
			parent.children.put(frameId, node);
			nodeCount++;
		}
		return node;
	}

	private void grow() {
		int size = Math.min(stack.length * 2, maxDepth + 1);
		Node[] ns = new Node[size];
		long[] ne = new long[size];
		long[] nc = new long[size];
		System.arraycopy(stack, 0, ns, 0, stack.length);
		System.arraycopy(entryOps, 0, ne, 0, entryOps.length);
		System.arraycopy(childOps, 0, nc, 0, childOps.length);
		stack = ns;
		entryOps = ne;
		childOps = nc;
	}
}
