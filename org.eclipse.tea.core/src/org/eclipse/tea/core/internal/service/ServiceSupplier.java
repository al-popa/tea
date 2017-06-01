/*******************************************************************************
 * Copyright (c) 2014 BestSolution.at and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Tom Schindl <tom.schindl@bestsolution.at> - initial API and implementation
 *     Markus Duft <markus.duft@ssi-schaefer.com> - adpation for TEA: proper sorting by rank
 *******************************************************************************/
package org.eclipse.tea.core.internal.service;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.e4.core.di.IInjector;
import org.eclipse.e4.core.di.suppliers.ExtendedObjectSupplier;
import org.eclipse.e4.core.di.suppliers.IObjectDescriptor;
import org.eclipse.e4.core.di.suppliers.IRequestor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Component;

/**
 * Supplier for {@link Service}. Copied from E(fx)clipse project.
 */
@SuppressWarnings("restriction")
@Component(service = ExtendedObjectSupplier.class, property = "dependency.injection.annotation=org.eclipse.tea.core.internal.service.Service")
public class ServiceSupplier extends ExtendedObjectSupplier {

	static class ServiceHandler implements ServiceListener {
		private final ServiceSupplier supplier;
		final Set<IRequestor> requestors = new HashSet<>();
		private final BundleContext bundleContext;
		private final Class<?> serviceType;

		public ServiceHandler(ServiceSupplier supplier, BundleContext bundleContext, Class<?> serviceType) {
			this.supplier = supplier;
			this.bundleContext = bundleContext;
			this.serviceType = serviceType;
		}

		@Override
		public void serviceChanged(ServiceEvent event) {
			synchronized (this.supplier) {
				Predicate<IRequestor> pr = IRequestor::isValid;
				this.requestors.removeIf(pr.negate());

				if (this.requestors.isEmpty()) {
					Map<Class<?>, ServiceHandler> map = this.supplier.handlerList.get(this.bundleContext);
					if (map != null) {
						map.remove(this.serviceType);

						if (map.isEmpty()) {
							this.supplier.handlerList.remove(this.bundleContext);
						}
					}

					this.bundleContext.removeServiceListener(this);
					return;
				}

				String[] data = (String[]) event.getServiceReference().getProperty(Constants.OBJECTCLASS);
				for (String d : data) {
					if (this.serviceType.getName().equals(d)) {
						this.requestors.forEach(r -> {
							try {
								r.resolveArguments(false);
								r.execute();
							} catch (Throwable t) {
								t.printStackTrace();
							}
						});
						break;
					}
				}
			}
		}
	}

	Map<BundleContext, Map<Class<?>, ServiceHandler>> handlerList = new HashMap<>();

	private static final Comparator<ServiceReference<?>> COMPARATOR = (a, b) -> {
		int ra = parseRanking(a.getProperty(Constants.SERVICE_RANKING));
		int rb = parseRanking(b.getProperty(Constants.SERVICE_RANKING));

		int x = Integer.compare(ra, rb);
		if (x != 0) {
			return x;
		}

		return a.getClass().getName().compareTo(b.getClass().getName());
	};

	private static int parseRanking(Object o) {
		if (o instanceof Integer) {
			return (int) o;
		}
		int result = 0;
		try {
			result = Integer.parseInt(o.toString());
		} catch (Exception e) {
			// invalid ranking - ignore - OSGi will warn in this case.
		}
		return result;
	}

	@Override
	public Object get(IObjectDescriptor descriptor, IRequestor requestor, boolean track, boolean group) {
		Type desiredType = descriptor.getDesiredType();
		Bundle b = FrameworkUtil.getBundle(requestor.getRequestingObjectClass());

		if (desiredType instanceof ParameterizedType) {
			ParameterizedType t = (ParameterizedType) desiredType;
			if (t.getRawType() == Collections.class || t.getRawType() == List.class) {

				return handleCollection(b, t.getActualTypeArguments()[0], requestor,
						track && descriptor.getQualifier(Service.class).dynamic());
			}
		}

		return handleSingle(b, desiredType, requestor, descriptor,
				track && descriptor.getQualifier(Service.class).dynamic());
	}

	private Object handleSingle(Bundle bundle, Type t, IRequestor requestor, IObjectDescriptor descriptor,
			boolean track) {
		BundleContext context = bundle.getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(getClass()).getBundleContext();
		}

		@SuppressWarnings("unchecked")
		Class<Object> cl = t instanceof ParameterizedType ? (Class<Object>) ((ParameterizedType) t).getRawType()
				: (Class<Object>) t;
		try {
			{
				ServiceReference<?>[] serviceReferences = context.getServiceReferences(cl.getName(), null);
				if (serviceReferences != null) {
					Arrays.sort(serviceReferences, COMPARATOR);

					if (serviceReferences.length > 0) {
						if (track) {
							trackService(context, cl, requestor);
						}
						return context.getService(serviceReferences[serviceReferences.length - 1]);
					}
				}
			}
		} catch (InvalidSyntaxException e) {
			// cannot happen: no filter used
		}

		return IInjector.NOT_A_VALUE;
	}

	private List<Object> handleCollection(Bundle bundle, Type t, IRequestor requestor, boolean track) {
		List<Object> rv = new ArrayList<>();

		BundleContext context = bundle.getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(getClass()).getBundleContext();
		}

		@SuppressWarnings("unchecked")
		Class<Object> cl = t instanceof ParameterizedType ? (Class<Object>) ((ParameterizedType) t).getRawType()
				: (Class<Object>) t;
		try {
			ServiceReference<?>[] serviceReferences = context.getServiceReferences(cl.getName(), null);
			if (serviceReferences != null) {
				Arrays.sort(serviceReferences, COMPARATOR);

				for (ServiceReference<?> serviceReference : serviceReferences) {
					rv.add(context.getService(serviceReference));
				}
			}

			// We are in the wrong order
			Collections.reverse(rv);

			if (track) {
				trackService(context, cl, requestor);
			}
		} catch (InvalidSyntaxException e) {
			// cannot happen: no filter used
		}

		return rv;
	}

	private synchronized void trackService(BundleContext context, Class<?> serviceClass, IRequestor requestor) {
		Map<Class<?>, ServiceHandler> map = this.handlerList.computeIfAbsent(context, (k) -> new HashMap<>());
		ServiceHandler handler = map.computeIfAbsent(serviceClass, (cl) -> {
			ServiceHandler h = new ServiceHandler(this, context, serviceClass);
			context.addServiceListener(h);
			return h;
		});
		handler.requestors.add(requestor);
	}
}
