/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.virtual.phases.ea;

import java.util.ArrayList;

import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.ControlSinkNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.PhiNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.debug.DynamicCounterNode;
import com.oracle.graal.nodes.debug.WeakCounterNode;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.nodes.virtual.EscapeObjectState;
import com.oracle.graal.phases.common.DeadCodeEliminationPhase;

public class GraphEffectList extends EffectList {

    public void addCounterBefore(String group, String name, int increment, boolean addContext, FixedNode position) {
        add("add counter", graph -> DynamicCounterNode.addCounterBefore(group, name, increment, addContext, position));
    }

    public void addCounterAfter(String group, String name, int increment, boolean addContext, FixedWithNextNode position) {
        FixedNode nextPosition = position.next();
        add("add counter after", graph -> DynamicCounterNode.addCounterBefore(group, name, increment, addContext, nextPosition));
    }

    public void addWeakCounterCounterBefore(String group, String name, int increment, boolean addContext, ValueNode checkedValue, FixedNode position) {
        add("add weak counter", graph -> WeakCounterNode.addCounterBefore(group, name, increment, addContext, checkedValue, position));
    }

    /**
     * Adds the given fixed node to the graph's control flow, before position (so that the original
     * predecessor of position will then be node's predecessor).
     *
     * @param node The fixed node to be added to the graph.
     * @param position The fixed node before which the node should be added.
     */
    public void addFixedNodeBefore(FixedWithNextNode node, FixedNode position) {
        add("add fixed node", graph -> {
            assert !node.isAlive() && !node.isDeleted() && position.isAlive();
            graph.addBeforeFixed(position, graph.add(node));
        });
    }

    public void ensureAdded(ValueNode node, FixedNode position) {
        add("ensure added", graph -> {
            assert position.isAlive();
            if (!node.isAlive()) {
                graph.addWithoutUniqueWithInputs(node);
                if (node instanceof FixedWithNextNode) {
                    graph.addBeforeFixed(position, (FixedWithNextNode) node);
                }
            }
        });
    }

    /**
     * Add the given floating node to the graph.
     *
     * @param node The floating node to be added.
     */
    public void addFloatingNode(ValueNode node, @SuppressWarnings("unused") String cause) {
        add("add floating node", graph -> graph.addWithoutUnique(node));
    }

    /**
     * Sets the phi node's input at the given index to the given value, adding new phi inputs as
     * needed.
     *
     * @param node The phi node whose input should be changed.
     * @param index The index of the phi input to be changed.
     * @param value The new value for the phi input.
     */
    public void initializePhiInput(PhiNode node, int index, ValueNode value) {
        add("set phi input", (graph, obsoleteNodes) -> {
            assert node.isAlive() && value.isAlive() && index >= 0;
            node.initializeValueAt(index, value);
        });
    }

    /**
     * Adds a virtual object's state to the given frame state. If the given reusedVirtualObjects set
     * contains the virtual object then old states for this object will be removed.
     *
     * @param node The frame state to which the state should be added.
     * @param state The virtual object state to add.
     */
    public void addVirtualMapping(FrameState node, EscapeObjectState state) {
        add("add virtual mapping", new Effect() {
            @Override
            public void apply(StructuredGraph graph, ArrayList<Node> obsoleteNodes) {
                if (node.isAlive()) {
                    assert !state.isDeleted();
                    FrameState stateAfter = node;
                    for (int i = 0; i < stateAfter.virtualObjectMappingCount(); i++) {
                        if (stateAfter.virtualObjectMappingAt(i).object() == state.object()) {
                            stateAfter.virtualObjectMappings().remove(i);
                        }
                    }
                    stateAfter.addVirtualObjectMapping(state.isAlive() ? state : graph.unique(state));
                }
            }

            @Override
            public boolean isVisible() {
                return false;
            }
        });
    }

    /**
     * Removes the given fixed node from the control flow and deletes it.
     *
     * @param node The fixed node that should be deleted.
     */
    public void deleteNode(Node node) {
        add("delete fixed node", (graph, obsoleteNodes) -> {
            if (node instanceof FixedWithNextNode) {
                GraphUtil.unlinkFixedNode((FixedWithNextNode) node);
            }
            obsoleteNodes.add(node);
        });
    }

    public void killIfBranch(IfNode ifNode, boolean constantCondition) {
        add("kill if branch", new Effect() {
            @Override
            public void apply(StructuredGraph graph, ArrayList<Node> obsoleteNodes) {
                graph.removeSplitPropagate(ifNode, ifNode.getSuccessor(constantCondition));
            }

            @Override
            public boolean isCfgKill() {
                return true;
            }
        });
    }

    public void replaceWithSink(FixedWithNextNode node, ControlSinkNode sink) {
        add("kill if branch", new Effect() {
            @Override
            public void apply(StructuredGraph graph, ArrayList<Node> obsoleteNodes) {
                node.replaceAtPredecessor(sink);
                GraphUtil.killCFG(node);
            }

            @Override
            public boolean isCfgKill() {
                return true;
            }
        });
    }

    /**
     * Replaces the given node at its usages without deleting it. If the current node is a fixed
     * node it will be disconnected from the control flow, so that it will be deleted by a
     * subsequent {@link DeadCodeEliminationPhase}
     *
     * @param node The node to be replaced.
     * @param replacement The node that should replace the original value. If the replacement is a
     *            non-connected {@link FixedWithNextNode} it will be added to the control flow.
     *
     */
    public void replaceAtUsages(ValueNode node, ValueNode replacement) {
        assert node != null && replacement != null : node + " " + replacement;
        add("replace at usages", (graph, obsoleteNodes) -> {
            assert node.isAlive() && replacement.isAlive() : node + " " + replacement;
            if (replacement instanceof FixedWithNextNode && ((FixedWithNextNode) replacement).next() == null) {
                assert node instanceof FixedNode;
                graph.addBeforeFixed((FixedNode) node, (FixedWithNextNode) replacement);
            }
            node.replaceAtUsages(replacement);
            if (node instanceof FixedWithNextNode) {
                GraphUtil.unlinkFixedNode((FixedWithNextNode) node);
            }
            obsoleteNodes.add(node);
        });
    }

    /**
     * Replaces the first occurrence of oldInput in node with newInput.
     *
     * @param node The node whose input should be changed.
     * @param oldInput The value to look for.
     * @param newInput The value to replace with.
     */
    public void replaceFirstInput(Node node, Node oldInput, Node newInput) {
        assert node.isAlive() && oldInput.isAlive() && !newInput.isDeleted();
        add("replace first input", new Effect() {
            @Override
            public void apply(StructuredGraph graph, ArrayList<Node> obsoleteNodes) {
                if (node.isAlive()) {
                    assert oldInput.isAlive() && newInput.isAlive();
                    node.replaceFirstInput(oldInput, newInput);
                }
            }

            @Override
            public boolean isVisible() {
                return !(node instanceof FrameState);
            }
        });
    }
}
