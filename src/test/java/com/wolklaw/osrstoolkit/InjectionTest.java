package com.wolklaw.osrstoolkit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import javax.inject.Inject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What Guice will accept, checked without starting a client.
 *
 * This exists because of a failure the compiler and every other test here were happy with. A
 * field was deleted and the {@code @Inject} above it was not, so the annotation slid onto the
 * next declaration down — a {@code final} map, initialised in place, that Guice cannot inject
 * and refuses to try. Java compiles that without complaint, the unit tests never build an
 * injector, and the only symptom was the plugin vanishing from RuneLite's list entirely: not
 * failing, not erroring in the panel, simply absent.
 */
public class InjectionTest
{
	@Test
	public void everyInjectedFieldIsSomethingGuiceCanActuallySet()
	{
		for (Field field : OsrsToolkitSyncPlugin.class.getDeclaredFields())
		{
			if (!field.isAnnotationPresent(Inject.class))
			{
				continue;
			}
			int modifiers = field.getModifiers();
			assertFalse(
				field.getName() + " is @Inject and final — Guice refuses the whole plugin for "
					+ "this, which reads as the plugin not existing",
				Modifier.isFinal(modifiers)
			);
			assertFalse(
				field.getName() + " is @Inject and static",
				Modifier.isStatic(modifiers)
			);
		}
	}

	@Test
	public void theFieldsGuiceHasToSupplyAreTheOnesItKnowsAbout()
	{
		// A guard on the shape rather than the count: anything newly injected should be a
		// RuneLite service or a config, not an arbitrary collection that happened to inherit
		// an annotation from the line above it.
		for (Field field : OsrsToolkitSyncPlugin.class.getDeclaredFields())
		{
			if (!field.isAnnotationPresent(Inject.class))
			{
				continue;
			}
			String type = field.getType().getName();
			assertTrue(
				"nothing should be asking Guice for " + type,
				type.startsWith("net.runelite.")
					|| type.startsWith("okhttp3.")
					|| type.startsWith("com.google.gson.")
					|| type.equals(OsrsToolkitSyncConfig.class.getName())
			);
		}
	}
}
