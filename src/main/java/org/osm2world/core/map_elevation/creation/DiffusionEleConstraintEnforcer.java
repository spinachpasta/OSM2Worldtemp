package org.osm2world.core.map_elevation.creation;

import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osm2world.core.map_elevation.data.EleConnector;
import org.osm2world.core.math.VectorXYZ;

/**
 * enforcer implementation that ignores many of the constraints, but is much
 * faster than the typical full implementation.
 *
 * It tries to produce an output that is "good enough" for some purposes, and is
 * therefore a compromise between the {@link NoneEleConstraintEnforcer} and a
 * full implementation.
 */
// implemented by spinachpasta
// This enforcer configures elavation using Diffusion equation (Heat equation)
public final class DiffusionEleConstraintEnforcer implements EleConstraintEnforcer {

	private Collection<EleConnector> connectors = new ArrayList<EleConnector>();

	/**
	 * associates each EleConnector with the {@link StiffConnectorSet} it is part of
	 * (if any)
	 */
	private Map<EleConnector, StiffConnectorSet> stiffSetMap = new HashMap<EleConnector, StiffConnectorSet>();

	int connectedCount = 0;

	private Map<EleConnector, List<EleConnector>> roadConnectedTo = new HashMap<EleConnector, List<EleConnector>>();
	private Map<EleConnector, Double> heightMap = new HashMap<EleConnector, Double>();

	private void addConnection(EleConnector from, EleConnector to) {
		EleConnector a = from;
		EleConnector b = to;
		if (a == b) {
			return;
		}
		for (int i = 0; i < 2; i++) {
			roadConnectedTo.putIfAbsent(a, new ArrayList<EleConnector>());
			List<EleConnector> connectors = roadConnectedTo.get(a);
			if (!connectors.contains(b)) {
				connectors.add(b);
			}
			// swap a and b
			EleConnector temp = a;
			a = b;
			b = temp;
		}
	}

	@Override
	public void addConnectors(Iterable<EleConnector> newConnectors) {

		for (EleConnector c : newConnectors) {
			connectors.add(c);
		}

		/* connect connectors */

		for (EleConnector c1 : newConnectors) {
			for (EleConnector c2 : connectors) {

				if (c1 != c2 && c1.connectsTo(c2)) {
					requireSameEle(c1, c2);
				}

			}
		}

	}

	@Override
	public void requireSameEle(EleConnector c1, EleConnector c2) {

		// SUGGEST (performance): a special case implementation would be faster

		requireSameEle(asList(c1, c2));

	}

	@Override
	public void requireSameEle(Iterable<EleConnector> cs) {

		/* find stiff sets containing any of the affected connectors */

		Set<EleConnector> looseConnectors = new HashSet<EleConnector>();
		Set<StiffConnectorSet> existingStiffSets = new HashSet<StiffConnectorSet>();

		for (EleConnector c : cs) {

			StiffConnectorSet stiffSet = stiffSetMap.get(c);

			if (stiffSet != null) {
				existingStiffSets.add(stiffSet);
			} else {
				looseConnectors.add(c);
			}

		}

		/* return if the connectors are already in a set together */

		if (existingStiffSets.size() == 1 && looseConnectors.isEmpty())
			return;

		/* merge existing sets (if any) into a single set */

		StiffConnectorSet commonStiffSet = null;

		if (existingStiffSets.isEmpty()) {
			commonStiffSet = new StiffConnectorSet();
		} else {

			for (StiffConnectorSet stiffSet : existingStiffSets) {

				if (commonStiffSet == null) {
					commonStiffSet = stiffSet;
				} else {

					for (EleConnector c : stiffSet) {
						stiffSetMap.put(c, commonStiffSet);
					}

					commonStiffSet.mergeFrom(stiffSet);

				}

			}

		}

		/* add remaining (loose) connectors into the common set */

		for (EleConnector c : looseConnectors) {

			commonStiffSet.add(c);

			stiffSetMap.put(c, commonStiffSet);

		}

	}

	@Override
	public void requireVerticalDistance(ConstraintType type, double distance, EleConnector upper, EleConnector lower) {
		// TODO Auto-generated method stub
		addConnection(upper, lower);

	}

	@Override
	public void requireVerticalDistance(ConstraintType type, double distance, EleConnector upper, EleConnector base1,
			EleConnector base2) {
		// TODO Auto-generated method stub
		addConnection(upper, base1);
		addConnection(upper, base2);

	}

	@Override
	public void requireIncline(ConstraintType type, double incline, List<EleConnector> cs) {
		// TODO Auto-generated method stub
		connectedCount++;
		for (int i = 0; i < cs.size() - 1; i++) {
			addConnection(cs.get(i), cs.get(i + 1));
		}
	}

	@Override
	public void requireSmoothness(EleConnector from, EleConnector via, EleConnector to) {
		// TODO Auto-generated method stub
		connectedCount++;
		addConnection(from, via);
		addConnection(via, to);
	}

	@Override
	public void enforceConstraints() {

		/* assign elevation to stiff sets by averaging terrain elevation */
		// TODO what for stiff sets above the ground?
		System.out.println("DiffusionEleConstraintEnforcer:enforceConstraints");
		for (StiffConnectorSet stiffSet : stiffSetMap.values()) {

			double averageEle = 0;

			for (EleConnector connector : stiffSet) {
				averageEle += connector.getPosXYZ().y;
			}

			averageEle /= stiffSet.size();

			for (EleConnector connector : stiffSet) {
				connector.setPosXYZ(connector.pos.xyz(averageEle));
			}

		}
		/*
		 * for (int i = 0; i < roadConnections.size(); i++) { RoadConnection con =
		 * roadConnections.get(i); for (int j = 0; j < con.connectors.size(); j++) {
		 * EleConnector me = con.connectors.get(j); if
		 * (!roadConnectedTo.containsKey(me)) { roadConnectedTo.put(me, new
		 * ArrayList<EleConnector>()); } List<EleConnector> targetList =
		 * roadConnectedTo.get(me); for (int k = 0; k < con.connectors.size(); k++) { if
		 * (j != k) { EleConnector connector=con.connectors.get(k);
		 * if(targetList.contains(connector)){ targetList.add(connector); } } }
		 * System.out.println(targetList.size()); } System.out.println(con); }
		 */
		/*
		 * for (Map.Entry<EleConnector, List<EleConnector>> entry :
		 * roadConnectedTo.entrySet()) { System.out.println(entry.getValue()); }
		 */

		for (EleConnector c : connectors) {
			// TODO use clearing

			switch (c.groundState) {
				case ABOVE:
					c.setPosXYZ(c.getPosXYZ().addY(5));
					// System.out.println("SimpleEleConstraintEnforcer:ABOVE");
					break;
				case BELOW:
					c.setPosXYZ(c.getPosXYZ().addY(-5));
					// System.out.println("SimpleEleConstraintEnforcer:BELOW");
					break;
				default: // stay at ground elevation
			}
			heightMap.put(c, c.getPosXYZ().y);
		}
		// integrator code
		double dt = 0.1;// dt
		for (int step = 0; step < 100; step++) {
			for (Map.Entry<EleConnector, List<EleConnector>> entry : roadConnectedTo.entrySet()) {
				EleConnector source = entry.getKey();
				if (source == null) {
					continue;
				}
				double dhdt = 0;// dh/dt
				double myheight = heightMap.get(source);
				List<EleConnector> others = entry.getValue();
				List<Double> coupling = new ArrayList<Double>();
				for (int i = 0; i < others.size(); i++) {
					EleConnector other = others.get(i);
					double cc = 0;
					coupling.add(cc);
					if (other == null) {
						continue;
					}
					double distance = other.pos.distanceTo(source.pos);
					// diverge avoidance
					if (distance < 0.3) {
						distance = 0.3;
					}
					cc = 1 / distance;
					coupling.set(0, cc);
					dhdt += (heightMap.get(other) - myheight) / others.size();
				}
				source.setPosXYZ(source.getPosXYZ().addY(dhdt * dt));
			}
			// apply constant temperature boundary
			for (EleConnector c : connectors) {
				// TODO use clearing

				switch (c.groundState) {
					case ABOVE: {
						VectorXYZ coord = c.getPosXYZ();
						c.setPosXYZ(new VectorXYZ(coord.x, 5, coord.z));
						// System.out.println("SimpleEleConstraintEnforcer:ABOVE");
						break;
					}
					case BELOW: {
						VectorXYZ coord = c.getPosXYZ();
						c.setPosXYZ(new VectorXYZ(coord.x, -5, coord.z));
						// System.out.println("SimpleEleConstraintEnforcer:BELOW");
						break;
					}
					default: // stay at ground elevation
				}
				heightMap.put(c, c.getPosXYZ().y);
			}
		}

		// System.out.print("connectedCount:"+connectedCount);
	}

	/**
	 * a set of connectors that are required to have the same elevation TODO or a
	 * precise vertical offset
	 */
	private static class StiffConnectorSet implements Iterable<EleConnector> {

		// TODO maybe look for a more efficient set implementation
		private Set<EleConnector> connectors = new HashSet<EleConnector>();

		/**
		 * adds a connector to this set, requiring it to be at the set's reference
		 * elevation
		 */
		public void add(EleConnector connector) {
			connectors.add(connector);
		}

		/**
		 * combines this set with another, and makes the other set unusable. This set
		 * will contain all {@link EleConnector}s from the other set afterwards.
		 */
		public void mergeFrom(StiffConnectorSet otherSet) {

			connectors.addAll(otherSet.connectors);

			// make sure that the other set cannot be used anymore
			otherSet.connectors = null;

		}

		public double size() {
			return connectors.size();
		}

		@Override
		public Iterator<EleConnector> iterator() {
			return connectors.iterator();
		}

	}

}
