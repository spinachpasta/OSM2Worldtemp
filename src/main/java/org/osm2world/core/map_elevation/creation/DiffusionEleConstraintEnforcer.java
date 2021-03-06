package org.osm2world.core.map_elevation.creation;

import static java.util.Arrays.asList;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;

import org.apache.batik.bridge.Bridge;
import org.apache.commons.lang.time.StopWatch;
import org.osm2world.core.map_data.data.MapNode;
import org.osm2world.core.map_data.data.MapWaySegment;
import org.osm2world.core.map_elevation.data.EleConnector;
import org.osm2world.core.map_elevation.data.GroundState;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.world.data.NodeWorldObject;
import org.osm2world.core.world.modules.BridgeModule;
import org.osm2world.core.world.modules.RoadModule;
import org.osm2world.core.world.modules.RoadModule.Road;
import org.osm2world.core.world.modules.RoadModule.RoadConnector;

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

	class RoadNetworkEdge {
		public EleConnector a;
		public EleConnector b;
		public double distance;

		public RoadNetworkEdge(EleConnector start, EleConnector end, double d) {
			a = start;
			b = end;
			distance = d;
		}
	}

	Map<EleConnector, List<RoadNetworkEdge>> roadGraph = new HashMap<EleConnector, List<RoadNetworkEdge>>();;

	// axis aligned boundary boxes
	class AABB {
		public int x1;
		public int z1;
		public int x2;
		public int z2;

		public AABB(int x1, int z1, int x2, int z2) {
			this.x1 = x1;
			this.x2 = x2;
			this.z1 = z1;
			this.z2 = z2;
			if (x1 > x2) {
				throw new RuntimeException("x2 should be greater than x1");
			}
			if (z1 > z2) {
				throw new RuntimeException("z2 should be greater than z1s");
			}
		}

		public boolean isInside(double x, double z) {
			return isInsideMargin(x, z, 0);
		}

		// more likely to be true
		public boolean isInsideMargin(double x, double z, int margin) {
			return (x1 - margin - x) * (x2 + margin - x) < 0 && (z1 - margin - z) * (z2 + margin - z) < 0;
		}

		public boolean equalsTo(AABB aabb1) {
			return aabb1.x1 == x1 && aabb1.x2 == x2 && aabb1.z1 == z1 && aabb1.z1 == z1;
		}
	}

	private AABB gridPoint(double x, double z, int size) {
		int x1 = (int) Math.floor(x / size) * size;
		int z1 = (int) Math.floor(z / size) * size;
		return new AABB(x1, z1, x1 + size, z1 + size);
	}

	private Map<AABB, List<EleConnector>> createAABBMap(Iterable<EleConnector> newConnectors) {
		Map<AABB, List<EleConnector>> aabbs = new HashMap<AABB, List<EleConnector>>();
		// construct aabbs
		int eleconnectorCount = 0;
		{
			StopWatch stopWatch = new StopWatch();
			stopWatch.start();
			long last = System.currentTimeMillis();
			int count = 0;
			for (EleConnector c : newConnectors) {
				VectorXYZ pos = c.getPosXYZ();
				count++;
				boolean found = false;
				for (AABB aabb : aabbs.keySet()) {
					if (aabb.isInside(pos.x, pos.z)) {
						found = true;
						break;
					}
				}
				if (!found) {
					List<EleConnector> cons = new ArrayList<EleConnector>();
					AABB aabb = gridPoint(pos.x, pos.z, 100);
					aabbs.put(aabb, cons);
				}
				long current = System.currentTimeMillis();
				if (current - last > 5000) {
					last = current;
					System.out.println("AABB contstruction loop: " + count + " :" + stopWatch);
				}
			}
			eleconnectorCount = count;
		}
		{
			StopWatch stopWatch = new StopWatch();
			stopWatch.start();
			long last = System.currentTimeMillis();
			int count = 0;
			// add EleConnectors to map
			for (EleConnector c : newConnectors) {
				VectorXYZ pos = c.getPosXYZ();
				for (AABB aabb : aabbs.keySet()) {
					if (aabb.isInsideMargin(pos.x, pos.z, 1)) {
						aabbs.get(aabb).add(c);
					}
				}
				long current = System.currentTimeMillis();
				count++;
				if (current - last > 5000) {
					last = current;
					System.out.println(
							"AABB contstruction loop 2: " + count + "/" + eleconnectorCount + " :" + stopWatch);
				}
			}
		}
		return aabbs;
	}

	@Override
	public void addConnectors(Iterable<EleConnector> newConnectors) {

		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		{
			long last = System.currentTimeMillis();
			long count = 0;
			System.out.println("connectors.add(c)" + count + " :" + stopWatch);
			System.out.flush();
			for (EleConnector c : newConnectors) {
				connectors.add(c);
				count++;
				long current = System.currentTimeMillis();
				// System.out.println(current - last);
				if (current - last > 5000) {
					last = current;
					System.out.println("connectors.add(c)" + count + " :" + stopWatch);
				}
			}
		}

		/* connect connectors */

		// old implementation O(n^2)

		// for (EleConnector c1 : newConnectors) {
		// for (EleConnector c2 : connectors) {

		// if (c1 != c2 && c1.connectsTo(c2)) {
		// requireSameEle(c1, c2);
		// }

		// }
		// }
		{
			long last = System.currentTimeMillis();
			System.out.println("createAABBMap");
			Map<AABB, List<EleConnector>> map = createAABBMap(connectors);
			for (List<EleConnector> pconnectors : map.values()) {// parts of connector list
				for (EleConnector c1 : pconnectors) {
					for (EleConnector c2 : pconnectors) {
						if (c1 != c2 && c1.connectsTo(c2)) {
							requireSameEle(c1, c2);
						}
					}
				}
				long current = System.currentTimeMillis();
				if (current - last > 5000) {
					last = current;
					System.out.println("AABB:" + stopWatch + "/" + map.size());
					System.out.flush();
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

	}

	@Override
	public void requireVerticalDistance(ConstraintType type, double distance, EleConnector upper, EleConnector base1,
			EleConnector base2) {
		// TODO Auto-generated method stub
	}

	@Override
	public void requireIncline(ConstraintType type, double incline, List<EleConnector> cs) {
		// TODO Auto-generated method stub
	}

	@Override
	public void requireSmoothness(EleConnector from, EleConnector via, EleConnector to) {
		// TODO Auto-generated method stub
	}

	@Override
	public void enforceConstraints() {

		/* assign elevation to stiff sets by averaging terrain elevation */
		System.out.print("DiffusionEleConstraintEnforcer: StiffConnectorSet");
		System.out.flush();
		{
			long startTime = System.currentTimeMillis();
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
			System.out.println(" " + (System.currentTimeMillis() - startTime) + "ms");
		}
		System.out.println("initialize height map");
		System.out.flush();

		Map<MapNode, EleConnector> mapNodeToEleconnector = new HashMap<MapNode, EleConnector>();
		Map<EleConnector, Double> heightMap = new HashMap<EleConnector, Double>();
		Map<EleConnector, Double> heightMap1 = new HashMap<EleConnector, Double>();

		// initialize height map

		for (EleConnector c : connectors) {
			if (c.reference != null) {
				if (c.reference instanceof MapNode) {
					MapNode mn = (MapNode) c.reference;
					mapNodeToEleconnector.put(mn, c);
				}
			} else {
				// System.out.println(c);
			}
		}

		{
			System.out.println("creating graph...");
			System.out.flush();
			long startTime = System.currentTimeMillis();
			long lastTime = startTime;
			int count = 0;
			for (EleConnector c : connectors) {
				count++;
				if (c.reference instanceof MapNode) {
					MapNode mn = (MapNode) c.reference;
					List<Road> roads = RoadModule.getConnectedRoads(mn, false);// requirelanes?
					roads.forEach((road) -> {
						MapNode start = road.getPrimaryMapElement().getStartNode();
						MapNode end = road.getPrimaryMapElement().getEndNode();
						if (!mapNodeToEleconnector.containsKey(start) || !mapNodeToEleconnector.containsKey(end)) {
							// System.out.println("not contained in map");
							return;
						}
						EleConnector a = mapNodeToEleconnector.get(start);
						EleConnector b = mapNodeToEleconnector.get(end);

						List<EleConnector> center = road.getCenterlineEleConnectors();
						if (center.size() == 0) {
							// if (true) {
							addConnectionToGraph(c, b);
							addConnectionToGraph(c, a);
						} else {
							if (c.getPosXYZ().distanceToXZ(b.getPosXYZ()) > c.getPosXYZ().distanceToXZ(a.getPosXYZ())) {
								addConnectionToGraph(c, a);
							} else {
								addConnectionToGraph(c, b);
							}
							addConnectionToGraph(a, center.get(0));
							for (int i = 0; i < center.size() - 1; i++) {
								addConnectionToGraph(center.get(i), center.get(i + 1));
							}
							addConnectionToGraph(center.get(center.size() - 1), b);
						}
						// AddConnection(a, b, heightMap);
					});
				}
				long current = System.currentTimeMillis();
				if (current - lastTime > 5000) {
					System.out.println(
							"creating graph:" + (count) + "/" + connectors.size() + " " + (current - startTime) + "ms");
					lastTime = current;
				}
			}
			System.out.println("creating graph:" + (System.currentTimeMillis() - startTime) + "ms");
			System.out.flush();
		}
		for (EleConnector c : connectors) {
			double h = 0;
			if (c == null || c.groundState == null) {
				heightMap.put(c, h);
				heightMap1.put(c, h);
				continue;
			}
			switch (c.groundState) {
				case ABOVE:
					h = 5.0;
					break;
				case BELOW:
					h = -5.0;
					break;
				default: // stay at ground elevation
			}
			heightMap.put(c, h);
			heightMap1.put(c, h);
		}
		double dt = 0.01;
		double conductance = 1;
		{
			long startTime = System.currentTimeMillis();
			long lastTime = startTime;
			for (double t = 0; t < 100; t += dt) {
				for (EleConnector c : connectors) {
					double h = heightMap.get(c);
					if (c == null || c.groundState == null) {
						heightMap.put(c, h);
						heightMap1.put(c, h);
						continue;
					}
					switch (c.groundState) {
						case ABOVE:
							h = 5.0;
							break;
						case BELOW:
							h = -5.0;
							break;
						default: // stay at ground elevation
					}
					// heightMap.put(c, h);
					// heightMap1.put(c, h);
				}
				for (EleConnector a : roadGraph.keySet()) {
					double dhdt = 0;
					double h = heightMap.get(a);
					for (RoadNetworkEdge edge : roadGraph.get(a)) {
						dhdt -= conductance / Math.max(edge.distance * edge.distance, 5) * (h - heightMap.get(edge.b));
					}
					heightMap1.put(a, dhdt * dt + h);
				}
				for (EleConnector c : connectors) {
					heightMap.put(c, heightMap1.get(c));
				}
				long current = System.currentTimeMillis();
				if (current - lastTime > 5000) {
					lastTime = current;
					System.out.println("t=" + t + "diffusion time:" + (System.currentTimeMillis() - startTime) + "ms");
					System.out.flush();
				}
			}
		}
		// apply heights to side lane
		for (EleConnector c : connectors) {
			if (c.reference instanceof MapNode) {
				MapNode mn = (MapNode) c.reference;
				List<Road> roads = RoadModule.getConnectedRoads(mn, false);// requirelanes?
				roads.forEach((road) -> {
					EleConnector start = mapNodeToEleconnector.get(road.getPrimaryMapElement().getStartNode());
					EleConnector end = mapNodeToEleconnector.get(road.getPrimaryMapElement().getEndNode());
					List<EleConnector> center = new ArrayList<EleConnector>(road.getCenterlineEleConnectors());

					if (center.size() == 0) {
						center = new ArrayList<EleConnector>();
						center.add(start);
						center.add(end);
					} else {
						if (center.get(0) != start) {
							center.add(0, start);
						}
						if (center.get(center.size() - 1) != end) {
							center.add(center.size() - 1, end);
						}
					}
					try {
						List<EleConnector> left = road.connectors.getConnectors(road.getOutlineXZ(false));
						if (left.size() != center.size()) {
							// System.out.println(left.size() + "," + center.size());
						}
						copyLane(heightMap, center, left);
					} catch (Exception e) {
					}
					try {
						List<EleConnector> right = road.connectors.getConnectors(road.getOutlineXZ(true));
						if (right.size() != center.size()) {
							// System.out.println(right.size() + "," + center.size());
						}
						copyLane(heightMap, center, right);
					} catch (Exception e) {
					}
				});
			}
		}
		for (EleConnector c : connectors) {
			double h = heightMap.get(c);
			c.setPosXYZ(c.getPosXYZ().addY(h));
		}
	}

	private void copyLane(Map<EleConnector, Double> heightMap, List<EleConnector> from, List<EleConnector> to) {
		if (to.size() == 0) {
			return;
		}
		if (from.size() == 0) {
			return;
		}

		double conversionratio = (double) from.size() / to.size();
		for (int i = 0; i < to.size(); i++) {
			int i_from = (int) Math.round((double) (i * conversionratio));
			i_from = i_from >= from.size() ? from.size() : i_from;
			heightMap.put(to.get(i), heightMap.get(from.get(i_from)));
		}
	}

	private void interpolateConnection(Map<EleConnector, Double> heightMap, List<EleConnector> center,
			EleConnector start, EleConnector end, boolean isSide) {
		if (center.size() == 0) {
			return;
		}
		RoadNetworkEdge edge = null;
		for (RoadNetworkEdge e : roadGraph.get(start)) {
			if (e.b == end) {
				edge = e;
				break;
			}
		}
		if (edge != null) {
			System.out.println(edge.h);
		}
		// calculate length of road
		// length will be used to calculate weights for linear interpolation
		double length = 0;
		if (isSide) {
			EleConnector before = center.get(0);
			for (int i = 0; i < center.size(); i++) {
				EleConnector segment = center.get(i);
				length += segment.getPosXYZ().distanceToXZ(before.getPosXYZ());
				before = segment;
			}
		} else {
			EleConnector before = start;
			for (int i = 0; i < center.size(); i++) {
				EleConnector segment = center.get(i);
				length += segment.getPosXYZ().distanceToXZ(before.getPosXYZ());
				before = segment;
			}
			length += end.getPosXYZ().distanceToXZ(before.getPosXYZ());
		}
		// System.out.println("length=" + length);

		{
			double pos = 0;
			double startHeight = heightMap.get(start);
			double endHeight = heightMap.get(end);
			EleConnector before = isSide ? center.get(0) : start;
			for (int i = 0; i < center.size(); i++) {
				EleConnector segment = center.get(i);
				pos += segment.getPosXYZ().distanceToXZ(before.getPosXYZ());
				heightMap.put(segment, endHeight * pos / length + startHeight * (1 - pos / length));
				before = segment;
			}
		}
	}

	// add connection to node edges
	private void addConnectionToGraph(EleConnector start, EleConnector end) {
		addConnectionToGraph(start, end, start.getPosXYZ().distanceToXZ(end.getPosXYZ()));
	}

	// add connection to node edges
	private void addConnectionToGraph(EleConnector start, EleConnector end, double distance) {
		EleConnector a = start, b = end;
		for (int i = 0; i < 2; i++) {
			if (roadGraph.containsKey(a)) {
				boolean found = false;
				List<RoadNetworkEdge> edges = roadGraph.get(a);
				for (RoadNetworkEdge edge : edges) {
					if (edge.a == a && edge.b == b) {
						found = true;
						break;
					}
					if (edge.a == b && edge.b == a) {
						found = true;
						break;
					}
				}
				if (!found) {
					edges.add(new RoadNetworkEdge(a, b, distance));
				}
			} else {
				List<RoadNetworkEdge> edges = new ArrayList<RoadNetworkEdge>();
				edges.add(new RoadNetworkEdge(a, b, distance));
				roadGraph.put(a, edges);
			}
			EleConnector temp = a;
			a = b;
			b = temp;
		}
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