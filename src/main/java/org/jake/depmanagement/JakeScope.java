package org.jake.depmanagement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.jake.utils.JakeUtilsIterable;

/**
 * Defines a context where is defined dependencies of a given project.
 * According we need to compile, test or run the application, the dependencies may diverge.
 * For example, <code>Junit</code> library may only be necessary for testing, so we
 * can declare that <code>Junit</scope> is only necessary for scope <code>TEST</code>.<br/>
 * This class predefines some standard scope as <code>COMPILE</code> or <code>RUNTIME</code>
 * but you may define you own.
 * 
 * Similar to Maven <code>scope</code> or Ivy <code>configuration</code>.
 * 
 * @author Djeang
 */
public final class JakeScope {

	/**
	 * Dependencies to compile the project but that should not be embedded in produced artifacts.
	 */
	public static JakeScope PROVIDED = JakeScope.of("provided");

	/**
	 * Dependencies to compile the project.
	 */
	public static JakeScope COMPILE = JakeScope.of("compile");

	/**
	 * Dependencies to embed in produced artifacts (as war or fat jar * files).
	 */
	public static JakeScope RUNTIME = JakeScope.of("runtime", COMPILE);

	/**
	 * Dependencies necessary to compile and run tests.
	 */
	public static JakeScope TEST = JakeScope.of("test", RUNTIME, PROVIDED);

	/**
	 * Stands for the artifacts produced by the build without any dependencies.
	 * This scope is primarily intended for scoping publication, not for dependencies dependenies.
	 */
	public static JakeScope MASTER = JakeScope.of("master");

	/**
	 * Default scope used for publishing artifacts along its runtime dependencies.
	 */
	public static JakeScope DEFAULT = JakeScope.of("default", MASTER, RUNTIME);

	/**
	 * Creates a new {@link JakeScope} passing its name and inherited scopes.
	 * @param name The name of the scope : should be unique within a build.
	 * @param inheritFroms Inherited scopes.
	 */
	@SuppressWarnings("unchecked")
	public static JakeScope of(String name, JakeScope ...inheritFroms) {
		return new JakeScope(name, Arrays.asList(inheritFroms), Collections.EMPTY_LIST);
	}

	private final List<JakeScope> inheritFrom;

	private final List<JakeScope> excluding;

	private final String name;

	private JakeScope(String name, List<JakeScope> inheritFrom, List<JakeScope> excluding) {
		super();
		this.inheritFrom = inheritFrom;
		this.name = name;
		this.excluding = excluding;
	}

	public JakeScope excluding(JakeScope... jakeScopes) {
		final List<JakeScope> exclues = new LinkedList<JakeScope>(this.excluding);
		exclues.addAll(Arrays.asList(jakeScopes));
		return new JakeScope(name, inheritFrom, exclues);
	}

	public String name() {
		return name;
	}

	public List<JakeScope> impliedScopes() {
		final List<JakeScope> list = new LinkedList<JakeScope>();
		list.add(this);
		for (final JakeScope scope : this.inheritFrom) {
			if (!excluding.contains(scope)) {
				for (final JakeScope jakeScope : scope.impliedScopes()) {
					if (!list.contains(jakeScope)) {
						list.add(jakeScope);
					}
				}

			}
		}
		return list;
	}

	public JakeScopeMapping mapTo(JakeScope targetScope) {
		return JakeScopeMapping.of(this, targetScope);
	}

	public JakeScopeMapping mapToDefault() {
		return JakeScopeMapping.of(this, DEFAULT);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final JakeScope other = (JakeScope) obj;
		if (name == null) {
			if (other.name != null) {
				return false;
			}
		} else if (!name.equals(other.name)) {
			return false;
		}
		return true;
	}

	@Override
	public String toString() {
		return "Scope:"+name;
	}



	public static class JakeScopeMapping implements Iterable<JakeScopeMapping.Item> {

		public static JakeScopeMapping of(JakeScope from, JakeScope to) {
			final List<Item> scopes = new ArrayList<Item>();
			scopes.add(new Item(from, to));
			return new JakeScopeMapping(scopes);
		}

		public static JakeScopeMapping of(JakeScope from, String to) {
			return of(from, JakeScope.of(to));
		}

		@SuppressWarnings("unchecked")
		public static JakeScopeMapping oneToOne() {
			return new JakeScopeMapping(Collections.EMPTY_LIST);
		}

		private final List<Item> list;

		private JakeScopeMapping(List<Item> list) {
			super();
			this.list = list;
		}

		public JakeScopeMapping and(JakeScope from, JakeScope to) {
			final List<Item> scopes = new ArrayList<Item>(this.list);
			scopes.add(new Item(from, to));
			return new JakeScopeMapping(scopes);
		}

		public JakeScopeMapping and(JakeScope from, String to) {
			return and(from, JakeScope.of(to));
		}

		public JakeScopeMapping and(JakeScope from) {
			return and(from, from);
		}

		public JakeScopeMapping minus(Iterable<JakeScopeMapping> mappings) {
			final List<Item> items = new LinkedList<Item>(this.list);
			for (final JakeScopeMapping mapping : mappings) {
				items.removeAll(mapping.list);
			}
			return new JakeScopeMapping(items);
		}


		public JakeScopeMapping and(Iterable<JakeScopeMapping> mappings) {
			final List<Item> items = new LinkedList<Item>(this.list);
			for (final JakeScopeMapping mapping : mappings) {
				items.addAll(mapping.list);
			}
			return new JakeScopeMapping(items);
		}

		@Override
		public Iterator<Item> iterator() {
			return list.iterator();
		}

		public Set<JakeScope> targetScopes(JakeScope sourceScope) {
			final HashSet<JakeScope> set = new HashSet<JakeScope>();
			for (final Item mapping : list) {
				if (sourceScope.equals(mapping.from)) {
					set.add(mapping.to);
				}
			}
			return set;
		}

		private static class Item implements Iterable<Item> {

			private final JakeScope from;
			private final JakeScope to;

			public Item(JakeScope from, JakeScope to) {
				super();
				this.from = from;
				this.to = to;
			}

			@Override
			public Iterator<Item> iterator() {
				return JakeUtilsIterable.of(this).iterator();
			}

			@Override
			public int hashCode() {
				final int prime = 31;
				int result = 1;
				result = prime * result
						+ ((from == null) ? 0 : from.hashCode());
				result = prime * result + ((to == null) ? 0 : to.hashCode());
				return result;
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj) {
					return true;
				}
				if (obj == null) {
					return false;
				}
				if (getClass() != obj.getClass()) {
					return false;
				}
				final Item other = (Item) obj;
				if (from == null) {
					if (other.from != null) {
						return false;
					}
				} else if (!from.equals(other.from)) {
					return false;
				}
				if (to == null) {
					if (other.to != null) {
						return false;
					}
				} else if (!to.equals(other.to)) {
					return false;
				}
				return true;
			}

			@Override
			public String toString() {
				return this.getClass().getSimpleName() + "[" + from + "," + "]";
			}

		}
	}

}