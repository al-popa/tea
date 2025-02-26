/*******************************************************************************
 *  Copyright (c) 2017 SSI Schaefer IT Solutions GmbH and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      SSI Schaefer IT Solutions GmbH
 *******************************************************************************/
package org.eclipse.tea.library.build.p2;

import java.io.File;
import java.net.URI;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.internal.p2.core.helpers.ServiceHelper;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.eclipse.pde.internal.core.target.P2TargetUtils;
import org.eclipse.tea.core.services.TaskingLog;
import org.eclipse.tea.library.build.internal.Activator;

@SuppressWarnings("restriction")
public class TargetPlatformHelper {

	public static Job setTargetPlatform(TaskingLog log, String tp, IProject bep, boolean user) {
		if (tp == null || tp.isEmpty()) {
			throw new IllegalStateException("no target platform set, skipping");
		}

		ITargetDefinition definition = findTargetDefinition(log, tp, bep);
		return setTargetPlatform(log, definition, user);
	}

	public static Job setTargetPlatform(TaskingLog log, ITargetDefinition definition, boolean user) {
		if (definition == null) {
			throw new IllegalStateException("cannot resolve target definition");
		}

		log.debug("loading target definition");
		final LoadTargetDefinitionJob job = new LoadTargetDefinitionJob(definition) {

			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				IStatus result = super.runInWorkspace(monitor);
				if (result == Status.OK_STATUS) {
					try {
						resolveTargetDefinition(getCurrentTargetDefinition(), monitor);
					} catch (OperationCanceledException e) {
						return Status.CANCEL_STATUS;
					}
				}
				return result;
			}
		};
		job.setUser(user);
		job.addJobChangeListener(new JobChangeAdapter() {

			@Override
			public void done(IJobChangeEvent event) {
				if (event.getResult() != Status.OK_STATUS) {
					Activator.log(IStatus.ERROR, "failed to set the target platform: " + event.getResult(), null);
				}
			}
		});
		job.schedule();
		return job;
	}

	public static ITargetDefinition findTargetDefinition(TaskingLog log, String tp, IProject bep) {
		final ITargetPlatformService service = getTargetPlatformService();
		ITargetDefinition definition = null;
		try {
			URI tpDef = null;
			if (bep != null) {
				IResource member = bep.findMember(tp);
				if (member != null && member.exists() && member.getType() == IResource.FILE) {
					tpDef = member.getLocationURI();
				}
			}

			if (tpDef == null) {
				File f = new File(tp);
				if (f.isFile()) {
					tpDef = f.toURI();
				}
			}

			log.debug("looking up target platform, trying file: " + tpDef);

			if (tpDef == null) {
				// the following code is more or less the same as used by SDC to
				// find installed
				// local targets
				File installLoc = P2InstallationHelper.getInstallLocation(log, tp);
				log.debug("creating target from IU install location: " + installLoc);

				definition = createTargetFromLocation(service, installLoc);
			} else {
				log.debug("loading target from: " + tpDef);
				ITargetHandle target = service.getTarget(tpDef);
				definition = target.getTargetDefinition();
			}
		} catch (Exception e) {
			throw new IllegalStateException("cannot resolve target definition", e);
		}
		return definition;
	}

	private static ITargetDefinition createTargetFromLocation(final ITargetPlatformService service, File installLoc)
			throws CoreException {
		ITargetDefinition definition;
		definition = service.newTarget();
		definition.setName("Anonymous Target for Build");
		ITargetLocation loc = service.newDirectoryLocation(installLoc.getAbsolutePath());
		definition.setTargetLocations(new ITargetLocation[] { loc });
		service.saveTargetDefinition(definition);
		return definition;
	}

	public static ITargetDefinition getCurrentTargetDefinition() throws CoreException {
		ITargetPlatformService service = getTargetPlatformService();
		return service.getWorkspaceTargetHandle().getTargetDefinition();
	}

	public static ITargetPlatformService getTargetPlatformService() {
		return (ITargetPlatformService) ServiceHelper.getService(Activator.getContext(),
				ITargetPlatformService.class.getName());
	}

	public static void resolveTargetDefinition(ITargetDefinition targetDefinition, IProgressMonitor mon)
			throws CoreException {
		for (ITargetLocation loc : targetDefinition.getTargetLocations()) {
			if (!isOK(loc.getStatus())) {
				// trigger the updates
				loc.resolve(targetDefinition, mon);
			}
		}

		// work around ITargetLocation.resolve() implementations not properly
		// waiting for the particular resolver jobs to finish
		IQueryResult<IInstallableUnit> ius = P2TargetUtils.getIUs(targetDefinition, mon);
		int count[] = { 0 };
		ius.forEach(i -> count[0]++);

		for (ITargetLocation loc : targetDefinition.getTargetLocations()) {
			if (!isOK(loc.getStatus())) {
				throw new RuntimeException(
						"Failed to resolve " + loc.getType() + "-type content for target definition '"
								+ targetDefinition.getName() + "': " + getMessage(loc.getStatus()));
			}
		}

	}

	private static boolean isOK(IStatus status) {
		return status == null ? false : status.isOK();
	}

	private static String getMessage(IStatus status) {
		return status == null ? "no status" : status.getMessage();
	}
}
