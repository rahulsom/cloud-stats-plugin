/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.cloudstats;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.ManagementLink;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Statistics of past cloud activities.
 */
public class CloudStatistics extends ManagementLink {

    private static final Logger LOGGER = Logger.getLogger(CloudStatistics.class.getName());

    @Extension @Restricted(NoExternalUse.class)
    public static final CloudStatistics stats = new CloudStatistics();
    @Extension @Restricted(NoExternalUse.class)
    public static final ProvisioningListener pl = new ProvisioningListener(stats);
    @Extension @Restricted(NoExternalUse.class)
    public static final OperationListener ol = new OperationListener(stats);
    @Extension @Restricted(NoExternalUse.class)
    public static final SlaveCompletionDetector scd = new SlaveCompletionDetector(stats);

    /*
     * The log itself uses synchronized collection, to manipulate single entry it needs to be explicitly synchronized.
     */
    private final @Nonnull CyclicThreadSafeCollection<ProvisioningActivity> log = new CyclicThreadSafeCollection<>(100);

    /**
     * Get the singleton instance.
     */
    public static final @Nonnull CloudStatistics get() {
        return stats;
    }

    public String getDisplayName() {
        return isActive() ? "Cloud Statistics" : null ;
    }

    @Override
    public String getIconFileName() {
        // TODO find an icon
        return isActive() ? "/plugin/implied-labels/icons/48x48/attribute.png" : null;
    }

    @Override
    public String getUrlName() {
        return "cloud-stats";
    }

    @Override
    public String getDescription() {
        return "Report of current and past provisioning activities";
    }

    public boolean isActive() {
        return !Jenkins.getInstance().clouds.isEmpty();
    }

    public List<ProvisioningActivity> getActivities() {
        return log.toList();
    }

    private @CheckForNull ProvisioningActivity forNode(NodeProvisioner.PlannedNode plannedNode) {
        for (ProvisioningActivity activity : log.toList()) {
            if (activity.isFor(plannedNode)) {
                return activity;
            }
        }

        LOGGER.warning("No activity tracked for planned node " + plannedNode.displayName);
        System.out.println(log.toList());

        // Either a bug or already rotated/cleared. TODO log this
        return null;
    }

    @Restricted(NoExternalUse.class)
    private static class ProvisioningListener extends CloudProvisioningListener {

        private final CloudStatistics stats;

        public ProvisioningListener(@Nonnull CloudStatistics stats) {
            this.stats = stats;
        }

        @Override
        public void onStarted(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            for (NodeProvisioner.PlannedNode plannedNode : plannedNodes) {
                System.out.println("PROVISIONING STARTED " + plannedNode.displayName);
                ProvisioningActivity activity = new ProvisioningActivity(cloud, plannedNode);
                stats.log.add(activity);
            }
        }

        @Override
        public void onComplete(NodeProvisioner.PlannedNode plannedNode, Node node) {
            System.out.println("PROVISIONING COMPLETED " + plannedNode.displayName);
            ProvisioningActivity activity = stats.forNode(plannedNode);
            if (activity != null) {
                // TODO attach listener logs
            }

            // TODO mark the node as provisioned by certain cloud so we can group running slaves by cloud/category later
        }

        @Override
        public void onFailure(NodeProvisioner.PlannedNode plannedNode, Throwable t) {
            System.out.println("PROVISIONING FAILED " + plannedNode.displayName);
            ProvisioningActivity activity = stats.forNode(plannedNode);
            if (activity != null) {
                // TODO attach listener logs
                activity.getPhaseExecution(ProvisioningActivity.Phase.PROVISIONING).attach(new PhaseExecutionAttachment.ExceptionAttachement(
                        ProvisioningActivity.Status.FAIL, "Provisioning failed", t
                ));
            }
        }
    }

    @Restricted(NoExternalUse.class)
    private static class OperationListener extends ComputerListener {

        private final CloudStatistics stats;

        public OperationListener(@Nonnull CloudStatistics stats) {
            this.stats = stats;
        }

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            System.out.println("LAUNCH STARTED " + c.getName());
        }

        @Override
        public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            System.out.println("LAUNCH FAILED " + c.getName());
//            ProvisioningActivity activity = stats.forNode(plannedNode);
//            if (activity != null) {
//                if (activity.getPhase() == ProvisioningActivity.Phase.OPERATING) return;
//            }
        }

        @Override
        public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            System.out.println("LAUNCH COMPLETED " + c.getName());
            System.out.println("OPERATION STARTED " + c.getName());
        }
    }

    // TODO Replace with real extension point https://issues.jenkins-ci.org/browse/JENKINS-33780
    @Restricted(NoExternalUse.class)
    private static class SlaveCompletionDetector extends PeriodicWork {

        // TODO better to detect operational activities its slave does not exists and report those as completed
        private volatile List<Computer> last = null;

        private final CloudStatistics stats;

        public SlaveCompletionDetector(@Nonnull CloudStatistics stats) {
            this.stats = stats;
        }

        @Override
        public long getRecurrencePeriod() {
            return MIN * 10;
        }

        @Override
        protected void doRun() throws Exception {
            if (last == null) return;

            List<Computer> current = Arrays.asList(Jenkins.getInstance().getComputers());
            List<Computer> deleted = last;
            last.removeAll(current);

            for (Computer c : deleted) {
                System.out.println("OPERATION Completed " + c.getName());
            }

            last = new ArrayList<>(current);
        }
    }

    private static final class CloudIdProperty extends NodeProperty {

    }
}
