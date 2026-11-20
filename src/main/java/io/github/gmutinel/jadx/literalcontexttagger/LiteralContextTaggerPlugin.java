package io.github.gmutinel.jadx.literalcontexttagger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.pass.JadxPassInfo;
import jadx.api.plugins.pass.impl.SimpleJadxPassInfo;
import jadx.api.plugins.pass.types.JadxDecompilePass;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.LiteralArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.InsnUtils;

/**
 * jadx plugin that tags bare numeric/boolean literal call-site arguments
 * passed to a curated list of well-known Android setter methods with their
 * semantic context, so that otherwise-invisible constant-value changes
 * (e.g. an alpha value passed to {@code View.setAlpha()}) become
 * identifiable in a diff.
 *
 * <p>Runs as a {@link JadxDecompilePass}: for every {@code INVOKE} instruction
 * in a method whose target method name matches the allowlist, resolves each
 * argument via {@link InsnUtils#getConstValueByArg} — if it resolves to a
 * {@link LiteralArg}, the literal is tagged with its call-site context.
 */
public class LiteralContextTaggerPlugin implements JadxPlugin {

	private static final Logger LOG = LoggerFactory.getLogger(LiteralContextTaggerPlugin.class);
	private static final String OUTPUT_FILE_NAME = "literal_contexts.json";

	private static final Set<String> WATCHED_SETTERS = new HashSet<>(Arrays.asList(
			"setAlpha", "setVisibility", "setEnabled", "setBackgroundColor", "setTextColor",
			"setScaleX", "setScaleY", "setTranslationX", "setTranslationY", "setDuration", "setElevation"));

	/**
	 * Filled from jadx's decompile threads. jadx visits most methods more than once, and how many times depends on
	 * the thread schedule: identical records are kept once, in sorted order, so the sidecar is the same on every run.
	 */
	private final SortedSet<String> records = Collections.synchronizedSortedSet(new TreeSet<>());
	private volatile Path outputPath;
	private volatile RootNode rootNode;

	@Override
	public JadxPluginInfo getPluginInfo() {
		return new JadxPluginInfo("literal-context-tagger", "Literal Context Tagger",
				"Tags literal call-site arguments to well-known Android setters with semantic context");
	}

	@Override
	public void init(JadxPluginContext context) {
		context.addPass(new JadxDecompilePass() {
			@Override
			public JadxPassInfo getInfo() {
				return new SimpleJadxPassInfo("LiteralContextTagger", "Tag literal call-site arguments");
			}

			@Override
			public void init(RootNode root) {
				rootNode = root;
				Path outDir = root.getArgs().getOutDirSrc() != null
						? root.getArgs().getOutDirSrc().toPath()
						: root.getArgs().getOutDir().toPath();
				outputPath = outDir.resolve(OUTPUT_FILE_NAME);
			}

			@Override
			public boolean visit(ClassNode cls) {
				return true;
			}

			@Override
			public void visit(MethodNode mth) {
				try {
					tagLiterals(mth);
				} catch (Exception e) {
					LOG.debug("Failed to tag literals in method {}: {}", mth, e.getMessage());
				}
			}
		});
	}

	private void tagLiterals(MethodNode mth) {
		List<BlockNode> blocks = mth.getBasicBlocks();
		if (blocks == null) {
			return;
		}
		for (BlockNode block : blocks) {
			for (InsnNode insn : block.getInstructions()) {
				if (insn.getType() != InsnType.INVOKE) {
					continue;
				}
				InvokeNode invoke = (InvokeNode) insn;
				MethodInfo callMth = invoke.getCallMth();
				String methodName = callMth.getName();
				if (!WATCHED_SETTERS.contains(methodName)) {
					continue;
				}
				int argsCount = invoke.getArgsCount();
				for (int i = 0; i < argsCount; i++) {
					InsnArg arg = invoke.getArg(i);
					Object constVal = InsnUtils.getConstValueByArg(rootNode, arg);
					if (constVal instanceof LiteralArg) {
						records.add(toJson(mth, methodName, i, (LiteralArg) constVal));
					}
				}
			}
		}
	}

	private static String toJson(MethodNode mth, String callTarget, int argIndex, LiteralArg literal) {
		String cls = escape(mth.getParentClass().getClassInfo().getAliasFullName());
		String name = escape(mth.getAlias());
		String literalStr = escape(literal.toString());
		return String.format(Locale.ROOT,
				"{\"class\":\"%s\",\"method\":\"%s\",\"callTarget\":\"%s\",\"argIndex\":%d,\"literal\":\"%s\"}",
				cls, name, escape(callTarget), argIndex, literalStr);
	}

	/** A JSON string body: quote, backslash and every control character escaped. */
	private static String escape(String s) {
		StringBuilder b = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				default:
					if (c < 0x20) {
						b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					} else {
						b.append(c);
					}
			}
		}
		return b.toString();
	}

	@Override
	public void unload() {
		Path path = outputPath;
		List<String> sorted;
		synchronized (records) {
			sorted = new ArrayList<>(records);
		}
		if (path == null || sorted.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				w.write("[\n");
				for (int i = 0; i < sorted.size(); i++) {
					w.write("  ");
					w.write(sorted.get(i));
					w.write(i < sorted.size() - 1 ? ",\n" : "\n");
				}
				w.write("]\n");
			}
			LOG.info("Wrote {} literal context tags to {}", sorted.size(), path);
		} catch (IOException e) {
			LOG.error("Failed to write literal context tags", e);
		} finally {
			records.clear();
		}
	}
}
