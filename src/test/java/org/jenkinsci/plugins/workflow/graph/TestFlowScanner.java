/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.graph;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class TestFlowScanner {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule r = new JenkinsRule();

    public static Predicate<FlowNode> predicateMatchStepDescriptor(@Nonnull final String descriptorId) {
        Predicate<FlowNode> outputPredicate = new Predicate<FlowNode>() {
            @Override
            public boolean apply(FlowNode input) {
                if (input instanceof StepAtomNode) {
                    StepAtomNode san = (StepAtomNode)input;
                    StepDescriptor sd = san.getDescriptor();
                    return sd != null && descriptorId.equals(sd.getId());
                }
                return false;
            }
        };
        return outputPredicate;
    }

    Predicate<FlowNode> MATCH_ECHO_STEP = predicateMatchStepDescriptor("org.jenkinsci.plugins.workflow.steps.EchoStep");

    static final class CollectingVisitor implements FlowScanner.FlowNodeVisitor {
        ArrayList<FlowNode> visited = new ArrayList<FlowNode>();

        @Override
        public boolean visit(@Nonnull FlowNode f) {
            visited.add(f);
            return true;
        }

        public void reset() {
            this.visited.clear();
        }

        public ArrayList<FlowNode> getVisited() {
            return visited;
        }
    };

    /** Tests the basic scan algorithm, predicate use, start/stop nodes */
    @Test
    public void testSimpleScan() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "Convoluted");
        job.setDefinition(new CpsFlowDefinition(
                "sleep 2 \n" +
                "echo 'donothing'\n" +
                "echo 'doitagain'"
        ));
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        FlowExecution exec = b.getExecution();
        FlowScanner.AbstractFlowScanner[] scans = {new FlowScanner.LinearScanner(),
                new FlowScanner.DepthFirstScanner(),
                new FlowScanner.LinearBlockHoppingScanner()
//                new FlowScanner.ForkScanner()
        };

        List<FlowNode> heads = exec.getCurrentHeads();

        // Iteration tests
        for (FlowScanner.AbstractFlowScanner scan : scans) {
            System.out.println("Iteration test with scanner: "+scan.getClass());
            scan.setup(heads, null);

            for (int i=6; i>2; i--) {
                Assert.assertTrue(scan.hasNext());
                FlowNode f = scan.next();
                Assert.assertEquals(Integer.toString(i), f.getId());
            }

            FlowNode f2 = scan.next();
            Assert.assertFalse(scan.hasNext());
            Assert.assertEquals("2", f2.getId());
        }

        // Test expected scans with no stop nodes given (different ways of specifying none)
        for (FlowScanner.ScanAlgorithm sa : scans) {
            System.out.println("Testing class: "+sa.getClass());
            FlowNode node = sa.findFirstMatch(heads, null, MATCH_ECHO_STEP);
            Assert.assertEquals(exec.getNode("5"), node);
            node = sa.findFirstMatch(heads, Collections.EMPTY_LIST, MATCH_ECHO_STEP);
            Assert.assertEquals(exec.getNode("5"), node);
            node = sa.findFirstMatch(heads, Collections.EMPTY_SET, MATCH_ECHO_STEP);
            Assert.assertEquals(exec.getNode("5"), node);

            Collection<FlowNode> nodeList = sa.filteredNodes(heads, null, MATCH_ECHO_STEP);
            FlowNode[] expected = new FlowNode[]{exec.getNode("5"), exec.getNode("4")};
            Assert.assertArrayEquals(expected, nodeList.toArray());
            nodeList = sa.filteredNodes(heads, Collections.EMPTY_LIST, MATCH_ECHO_STEP);
            Assert.assertArrayEquals(expected, nodeList.toArray());
            nodeList = sa.filteredNodes(heads, Collections.EMPTY_SET, MATCH_ECHO_STEP);
            Assert.assertArrayEquals(expected, nodeList.toArray());
        }

        // Test with no matches
        for (FlowScanner.ScanAlgorithm sa : scans) {
            System.out.println("Testing class: "+sa.getClass());
            FlowNode node = sa.findFirstMatch(heads, null, (Predicate)Predicates.alwaysFalse());
            Assert.assertNull(node);

            Collection<FlowNode> nodeList = sa.filteredNodes(heads, null, (Predicate) Predicates.alwaysFalse());
            Assert.assertNotNull(nodeList);
            Assert.assertEquals(0, nodeList.size());
        }


        CollectingVisitor vis = new CollectingVisitor();
        // Verify we touch head and foot nodes too
        for (FlowScanner.ScanAlgorithm sa : scans) {
            System.out.println("Testing class: " + sa.getClass());
            Collection<FlowNode> nodeList = sa.filteredNodes(heads, null, (Predicate) Predicates.alwaysTrue());
            vis.reset();
            sa.visitAll(heads, vis);
            Assert.assertEquals(5, nodeList.size());
            Assert.assertEquals(5, vis.getVisited().size());
        }

        // Test with a stop node given, sometimes no matches
        Collection<FlowNode> noMatchEndNode = Collections.singleton(exec.getNode("5"));
        Collection<FlowNode> singleMatchEndNode = Collections.singleton(exec.getNode("4"));
        for (FlowScanner.ScanAlgorithm sa : scans) {
            FlowNode node = sa.findFirstMatch(heads, noMatchEndNode, MATCH_ECHO_STEP);
            Assert.assertNull(node);

            Collection<FlowNode> nodeList = sa.filteredNodes(heads, noMatchEndNode, MATCH_ECHO_STEP);
            Assert.assertNotNull(nodeList);
            Assert.assertEquals(0, nodeList.size());

            // Now we try with a stop list the reduces node set for multiple matches
            node = sa.findFirstMatch(heads, singleMatchEndNode, MATCH_ECHO_STEP);
            Assert.assertEquals(exec.getNode("5"), node);
            nodeList = sa.filteredNodes(heads, singleMatchEndNode, MATCH_ECHO_STEP);
            Assert.assertNotNull(nodeList);
            Assert.assertEquals(1, nodeList.size());
            Assert.assertEquals(exec.getNode("5"), nodeList.iterator().next());
        }
    }

    /** Tests the basic scan algorithm where blocks are involved */
    @Test
    public void testBlockScan() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "Convoluted");
        job.setDefinition(new CpsFlowDefinition(
            "echo 'first'\n" +
            "timeout(time: 10, unit: 'SECONDS') {\n" +
            "    echo 'second'\n" +
            "    echo 'third'\n" +
            "}\n" +
            "sleep 1"
        ));
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        Predicate<FlowNode> matchEchoStep = predicateMatchStepDescriptor("org.jenkinsci.plugins.workflow.steps.EchoStep");

        // Test blockhopping
        FlowScanner.LinearBlockHoppingScanner linearBlockHoppingScanner = new FlowScanner.LinearBlockHoppingScanner();
        Collection<FlowNode> matches = linearBlockHoppingScanner.filteredNodes(b.getExecution().getCurrentHeads(), null, matchEchoStep);

        // This means we jumped the blocks
        Assert.assertEquals(1, matches.size());

        FlowScanner.DepthFirstScanner depthFirstScanner = new FlowScanner.DepthFirstScanner();
        matches = depthFirstScanner.filteredNodes(b.getExecution().getCurrentHeads(), null, matchEchoStep);

        // Nodes all covered
        Assert.assertEquals(3, matches.size());
    }

    @Test
    public void blockJumpTest() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "Convoluted");
        job.setDefinition(new CpsFlowDefinition(
                "echo 'sample'\n" +
                "node {\n" +
                "    echo 'inside node'    \n" +
                "}"
        ));

        /** Flow structure (ID - type)
         2 - FlowStartNode (BlockStartNode)
         3 - Echostep
         4 - ExecutorStep (StepStartNode) - WorkspaceAction
         5 - ExecutorStep (StepStartNode) - BodyInvocationAction
         6 - Echostep
         7 - StepEndNode - startId (5)
         8 - StepEndNode - startId (4)
         9 - FlowEndNode
         */

        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        Collection<FlowNode> heads = b.getExecution().getCurrentHeads();
        FlowExecution exec = b.getExecution();

        FlowScanner.LinearBlockHoppingScanner hopper = new FlowScanner.LinearBlockHoppingScanner();
        FlowNode headCandidate = exec.getNode("7");
        hopper.setup(headCandidate, null);
        List<FlowNode> filtered = hopper.filteredNodes(Collections.singleton(headCandidate), null, MATCH_ECHO_STEP);
        Assert.assertEquals(2, filtered.size());

        filtered = hopper.filteredNodes(Collections.singleton(exec.getNode("8")), null, MATCH_ECHO_STEP);
        Assert.assertEquals(1, filtered.size());

        filtered = hopper.filteredNodes(Collections.singleton(exec.getNode("9")), null, MATCH_ECHO_STEP);
        Assert.assertEquals(1, filtered.size());
    }


    /** And the parallel case */
    @Test
    public void testParallelScan() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "Convoluted");
        job.setDefinition(new CpsFlowDefinition(
            "echo 'first'\n" +
            "def steps = [:]\n" +
            "steps['1'] = {\n" +
            "    echo 'do 1 stuff'\n" +
            "}\n" +
            "steps['2'] = {\n" +
            "    echo '2a'\n" +
            "    echo '2b'\n" +
            "}\n" +
            "parallel steps\n" +
            "echo 'final'"
        ));

        /** Flow structure (ID - type)
         2 - FlowStartNode (BlockStartNode)
         3 - Echostep
         4 - ParallelStep (StepStartNode) (start branches)
         6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
         7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
         8 - EchoStep, (branch 1) parent=6
         9 - StepEndNode, (end branch 1) startId=6, parentId=8
         10 - EchoStep, (branch 2) parentId=7
         11 - EchoStep, (branch 2) parentId = 10
         12 - StepEndNode (end branch 2)  startId=7  parentId=11,
         13 - StepEndNode (close branches), parentIds = 9,12, startId=4
         14 - EchoStep
         15 - FlowEndNode (BlockEndNode)
         */

        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        Collection<FlowNode> heads = b.getExecution().getCurrentHeads();

        FlowScanner.AbstractFlowScanner scanner = new FlowScanner.LinearScanner();
        Collection<FlowNode> matches = scanner.filteredNodes(heads, null, MATCH_ECHO_STEP);
        Assert.assertTrue(matches.size() == 3 || matches.size() == 4);  // Depending on ordering


        scanner = new FlowScanner.DepthFirstScanner();
        matches = scanner.filteredNodes(heads, null, MATCH_ECHO_STEP);
        Assert.assertEquals(5, matches.size());

        scanner = new FlowScanner.LinearBlockHoppingScanner();
        matches = scanner.filteredNodes(heads, null, MATCH_ECHO_STEP);
        Assert.assertEquals(0, matches.size());

        matches = scanner.filteredNodes(Collections.singleton(b.getExecution().getNode("14")), null);
        Assert.assertEquals(2, matches.size());
    }

}