package com.bdreview.platform.commerce;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/** Builds SRID-4326 JTS points the same way {@code BusinessService} does. */
final class GeoPoints {

    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private GeoPoints() {
    }

    static Point of(double latitude, double longitude) {
        Point p = FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(4326);
        return p;
    }
}
