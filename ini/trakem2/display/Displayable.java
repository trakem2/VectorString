/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Search;
import ini.trakem2.vector.Compare;

/** The class that any element to be drawn on a Display must extend. */
public abstract class Displayable extends DBObject {

	final protected AffineTransform at = new AffineTransform();

	/** Width and height of the data, not the bounding box. If the AffineTransform is different than identity, then the bounding box will be different. */
	protected double width = 0,
		         height = 0;

	protected boolean locked = false;
	protected String title;
	protected Color color = Color.yellow;
	protected float alpha = 1.0f; // from 0 to 1 (0 is full transparency)
	protected boolean visible = true;
	protected Layer layer;
	/** The Displayable objects this one is linked to. Can be null. */
	protected HashSet<Displayable> hs_linked = null;

	/** The table of key/value property pairs of this Displayable. */
	protected Map<String,String> props = null;

	/** The table of tables of properties: for any target Displayable, there is a set of properties specific of the relationship between this Displayable and the target one. */
	protected Map<Displayable,Map<String,String>> linked_props = null;

	/** The call back hooks to remove any linked properties in other Displayable instances when this Displayable is removed. */
	protected Set<Displayable> linked_props_origins = null;

	/** Set a key/valye property pair; to remove a property, set the value to null. */
	synchronized public boolean setProperty(final String key, final String value) {
		if (null == key) return false;
		if (null != props && null == value) {
			props.remove(key);
			if (props.isEmpty()) props = null;
		} else {
			if (null == props) props = new HashMap<String,String>();
			props.put(key, value);
		}
		return true;
	}

	/** If key is null or not found, returns null; otherwise returns the stored value for key. */
	public String getProperty(final String key) {
		return getProperty(key, null);
	}

	/** If key is null or not found, returns default_value; otherwise returns the stored value for key. */
	synchronized public String getProperty(final String key, final String default_value) {
		if (null == key || null == props) return default_value;
		final String val = props.get(key);
		if (null == val) return default_value;
		return val;
	}

	/** Returns a copy of this object's properties, or null if none. */
	synchronized public Map getProperties() {
		if (null == props) return null;
		return new HashMap(props);
	}

	/** Add a property that is specific to the relationship between this Displayable and the target, and will be deleted when the target Displayable is deleted. */
	synchronized public boolean setLinkedProperty(final Displayable target, final String key, final String value) {
		if (null == target || null == key) return false;
		if (target.project != this.project) {
			Utils.log("Cannot link to a Displayable from another project!");
			return false;
		}
		if (null != linked_props && null == value) {
			// Remove the key and cleanup if necessary
			Map<String,String> p = linked_props.get(target);
			if (null != p) {
				p.remove(key);
				if (p.isEmpty()) {
					linked_props.remove(target);
					if (linked_props.isEmpty()) {
						linked_props = null;
					}
				}
			}
		} else {
			linkedProps(target).put(key, value);
		}
		return true;
	}

	/** Obtain the existing or a new, if null, map of linked properties with target. Also sets the remove callback hook to remove the map of linked properties when the target is removed. */
	private final Map<String,String> linkedProps(final Displayable target) {
		if (null == linked_props) linked_props = new HashMap<Displayable,Map<String,String>>();
		Map<String,String> p = linked_props.get(target);
		if (null == p) {
			p = new HashMap<String,String>();
			linked_props.put(target, p);
		}
		// Add hook on the target side: (so when the target is deleted, it will call removeLinkedProperties here)
		if (null == target.linked_props_origins) {
			target.linked_props_origins = new HashSet<Displayable>();
		}
		target.linked_props_origins.add(this);
		//
		return p;
	}

	/** Add all the properties in map p as properties linked to the target Displayable. If any keys already existed their values will be overwritten by those in p. Any keys whose value is null will be removed. */
	synchronized public void setLinkedProperties(final Displayable target, final Map<String,String> p) {
		if (null == target || null == p) return;
		if (target.project != this.project) {
			Utils.log("Cannot link to a Displayable from another project!");
			return;
		}
		final Map<String,String> lp = linkedProps(target);
		for (final Map.Entry<String,String> e : p.entrySet()) {
			final String value = e.getValue();
			if (null == value) {
				// Remove key from table
				lp.remove(e.getKey());
			} else {
				lp.put(e.getKey(), value);
			}
		}
		// clean up:
		if (lp.isEmpty()) {
			linked_props.remove(target);
		}
	}

	/** Removes and returns the table of properties lined to the target Displayable. May be empty. */
	synchronized public Map<String,String> removeLinkedProperties(final Displayable target) {
		if (null == target || null == linked_props) return new HashMap<String,String>();
		final Map<String,String> p = linked_props.remove(target);
		if (linked_props.isEmpty()) {
			linked_props = null;
		}
		if (null == p) return new HashMap<String,String>();
		return p;
	}

	/** Tell any other Displayable that has a linked property with this Displayable to remove it. */
	protected void removeLinkedPropertiesFromOrigins() {
		if (null != this.linked_props_origins) {
			for (final Displayable origin : linked_props_origins) {
				origin.removeLinkedProperties(this);
			}
		}
	}

	/** If the key is null or not found, or the aren't any properties linked to the target, returns null; otherwise returns the stored value for key and target. */
	public String getLinkedProperty(final Displayable target, final String key) {
		return getLinkedProperty(target, key, null);
	}

	/** If the key is null or not found, or the aren't any properties linked to the target, returns default_value; otherwise returns the stored value for key and target. */
	synchronized public String getLinkedProperty(final Displayable target, final String key, final String default_value) {
		if (null == target || null == key) return default_value;
		if (target.project != this.project) {
			Utils.log("You attempted to get a property for a Displayable of another project, which is impossible.");
			return default_value;
		}
		if (null == linked_props) return default_value;
		final Map<String,String> p = linked_props.get(target);
		if (null == p) return default_value;
		final String value = p.get(key);
		if (null == value) return default_value;
		return value;
	}

	/** Returns a copy of this object's linked properties table for the given Displayable, which may be empty. */
	synchronized public Map<String,String> getLinkedProperties(final Displayable target) {
		if (null == target || null == linked_props) return null;
		final Map<String,String> m = linked_props.get(target);
		if (null == m) return new HashMap<String,String>();
		return new HashMap<String,String>(m);
	}

	/** Returns a copy of this object's linked properties, which may be empty. */
	synchronized public Map<Displayable,Map<String,String>> getLinkedProperties() {
		final Map<Displayable,Map<String,String>> lp = new HashMap<Displayable,Map<String,String>>();
		if (null == linked_props) return lp;
		for (final Map.Entry<Displayable,Map<String,String>> e : linked_props.entrySet()) {
			lp.put(e.getKey(), new HashMap<String,String>(e.getValue()));
		}
		return lp;
	}

	////////////////////////////////////////////////////
	public void setLocked(boolean lock) {
		if (lock) this.locked = lock;
		else {
			// to unlock, unlock those in the linked group that are locked
			this.locked = false;
			unlockAllLinked(new HashSet());
		}
		updateInDatabase("locked");
	}
	private void unlockAllLinked(HashSet hs) {
		if (hs.contains(this)) return;
		hs.add(this);
		if (null == hs_linked) return;
		for (final Displayable d : hs_linked) {
			if (d.locked) d.locked = false;
			d.unlockAllLinked(hs);
		}
	}
	/** Return the value of the field 'locked'. */
	public boolean isLocked2() {
		return locked;
	}
	/** Check if this or any of the Displayables in the linked group is locked. */
	public boolean isLocked() {
		if (locked) return true;
		return isLocked(new HashSet());
	}
	private boolean isLocked(HashSet hs) {
		if (locked) return true;
		else if (hs.contains(this)) return false;
		hs.add(this);
		if (null != hs_linked && hs_linked.size() > 0) {
			for (final Displayable d : hs_linked) {
				if (d.isLocked(hs)) return true;
			}
		}
		return false;
	}
	////////////////////////////////////////////////////
	/** The minimal public Displayable constructor. */
	public Displayable(Project project, String title, double x, double y) {
		super(project);
		this.title = title;
		this.at.translate(x, y);
	}

	/** Reconstruct a Displayable from the database. */
	public Displayable(Project project, long id, String title, boolean locked, AffineTransform at, double width, double height) {
		super(project, id);
		this.title = title;
		this.locked = locked;
		if (null != at) this.at.setTransform(at);
		this.width = width;
		this.height = height;
	}

	/** Reconstruct a Displayable from an XML entry. Used entries get removed from the HashMap. */
	public Displayable(Project project, long id, HashMap ht, HashMap ht_links) {
		super(project, id);
		double x=0, y=0, rot=0; // for backward compatibility
		this.layer = null; // will be set later
		// parse data // TODO this is weird, why not just call them, since no default values are set anyway
		final ArrayList al_used_keys = new ArrayList();
		for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String data = (String)entry.getValue();
			try {
				if (key.equals("width")) width = Double.parseDouble(data);
				else if (key.equals("height")) height = Double.parseDouble(data);
				else if (key.equals("transform")) {
					final String[] nums = data.substring(data.indexOf('(')+1, data.lastIndexOf(')')).split(",");
					this.at.setTransform(Double.parseDouble(nums[0]), Double.parseDouble(nums[1]),
							     Double.parseDouble(nums[2]), Double.parseDouble(nums[3]),
							     Double.parseDouble(nums[4]), Double.parseDouble(nums[5]));
					//Utils.log2("at: " + this.at);
				}
				else if (key.equals("locked")) locked = data.trim().toLowerCase().equals("true");
				else if (key.equals("visible")) visible = data.trim().toLowerCase().equals("true");
				else if (key.equals("style")) {
					try {
					// 1 - extract alpha
					int i_start = data.indexOf("stroke-opacity:");
					if (-1 == i_start) {
						// try fill-opacity
						i_start = data.indexOf("fill-opacity:");
						if (-1 == i_start) {
							// default
							this.alpha = 1.0f;
						} else {
							this.alpha = Float.parseFloat(data.substring(i_start + 13, data.indexOf(';', i_start + 13)).trim());
						}
					} else {
						this.alpha = Float.parseFloat(data.substring(i_start + 15, data.indexOf(';', i_start + 15)).trim());
					}
					// 2 - extract color
					i_start = data.indexOf("stroke:");
					int i_end;
					if (-1 == i_start) {
						//try fill
						i_start = data.indexOf("fill:");
					}
					if (-1 != i_start) {
						i_start = data.indexOf('#', data.indexOf(':', i_start+4)); // at least 4 (for 'fill:'), max 7 (for 'stroke:')
						i_end = data.indexOf(';', i_start +1);
						this.color = Utils.getRGBColorFromHex(data.substring(i_start+1, i_end));
					} else {
						Utils.log2("Can't parse color for id=" + id);
						this.color = Color.yellow;
					}
					// avoid recording this key for deletion, for the DLabel to read it
					continue;
					} catch (Exception es) {
						if (null == this.color) this.color = Color.yellow;
						Utils.log("ERROR at reading style: " + es);
					}
				} else if (key.equals("links")) {
					// This is hard one, must be stored until all objects exist and then processed
					if (null != data && data.length() > 0) ht_links.put(this, data);
				} else if (key.equals("title")) {
					if (null != data && !data.toLowerCase().equals("null")) {
						this.title = data.replaceAll("^#^", "\""); // fix " and backslash characters
					} else this.title = null;
				} else if (key.equals("x")) {
					x = Double.parseDouble(data); // this could be done with reflection, but not all, hence this dullness
				} else if (key.equals("y")) {
					y = Double.parseDouble(data);
				} else if (key.equals("rot")) {
					rot = Double.parseDouble(data);
				} else continue;
				al_used_keys.add(key);
			} catch (Exception ea) {
				Utils.log(this + " : failed to read data for key '" + key + "':\n" + ea);
			}
		}
		for (Iterator it = al_used_keys.iterator(); it.hasNext(); ) {
			ht.remove(it.next());
		}

		// support old versions:
		if (this.at.isIdentity() && (0 != x || 0 != y || 0 != rot)) {
			this.at.translate(x, y);
			if (0 != rot) {
				AffineTransform at2 = new AffineTransform();
				at2.rotate(Math.toRadians(rot), x + width/2, y + height/2);
				this.at.preConcatenate(at2);
			}
		}
		// scaling in old versions will be lost
	}

	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
		Utils.log2("paint g, magnification, active, channels, active_layer: not implemented yet for " + this.getClass());
	}

	/** If the painting is expensive, children classes can override this method to provide first a coarse painting, and then call repaint on their own again once the desired graphics are ready. */
	public void prePaint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
		paint(g, magnification, active, channels, active_layer);
	}

	/** Not accepted if zero or negative. Remakes the snapshot, updates the snapshot panel and the Display. */
	public void setDimensions(double width, double height) {
		setDimensions(width, height, true);
	}

	/** Sets the dimensions of the bounding box. Not accepted if zero or negative. If repaint is true, remakes the snapshot, updates the snapshot panel and the Display. */
	public void setDimensions(double width, double height, boolean repaint) {
		if (width <= 0 || height <= 0) return;
		Rectangle b = getBoundingBox(null);
		if (b.width == width && b.height == height) return;
		double sx = width / (double)b.width;
		double sy = height / (double)b.height;
		this.scale(sx, sy, b.x, b.y); // relative to top left corner
		if (repaint) {
			Display.repaint(layer, this, 5);
			//done with above//Display.updatePanel(layer, this);
		}
	}

	public void setLayer(Layer layer, boolean update_db) {
		if (null == layer || this.layer == layer) return;
		this.layer = layer;
		if (update_db) updateInDatabase("layer_id");
	}

	public void setLayer(Layer layer) {
		setLayer(layer, true);
	}

	public Layer getLayer() {
		return layer;
	}

	/** Does not accept null or zero-length titles. */
	public void setTitle(String title) {
		if (null == title || 0 == title.length()) return;
		this.title = title;
		Display.updateTitle(layer, this); // update the DisplayablePanel(s) that show this Patch
		updateInDatabase("title");
	}

	public String getTitle() {
		return this.title;
	}

	public String getShortTitle() {
		Rectangle b = getBoundingBox(null);
		return "x=" + Utils.cutNumber(b.x, 2) + " y=" + Utils.cutNumber(b.y, 2) + (null != layer ? " z=" + Utils.cutNumber(layer.getZ(), 2) : "");
	}

	/** Returns the x of the bounding box. */
	public double getX() {
		return getBoundingBox(null).x;
	}

	/** Returns the y of the bounding box. */
	public double getY() {
		return getBoundingBox(null).y;
	}

	/** Returns the width of the data. */
	public double getWidth() {
		return this.width;
	}

	/** Returns the height of the data. */
	public double getHeight() {
		return this.height;
	}

	/** Bounding box of the transformed data. */
	public Rectangle getBoundingBox() {
		return getBoundingBox(null);
	}

	/** Will fill bounding box values into given rectangle  -- only that part of this object showing in the given layer will be included in the box. */
	public Rectangle getBounds(final Rectangle r, final Layer layer) {
		return getBoundingBox(r);
	}

	/** Bounding box of the transformed data. Saves one Rectangle allocation, returns the same Rectangle, modified (or a new one if null). */
	public Rectangle getBoundingBox(final Rectangle r) {
		return getBounds(null != r ? r : new Rectangle());
	}

	/** Bounding box of the transformed data. Saves one allocation, returns the same Rectangle, modified (or a new one if null). */
	private final Rectangle getBounds(final Rectangle r) {
		r.x = 0;
		r.y = 0;
		r.width = (int)this.width;
		r.height = (int)this.height;
		if (this.at.getType() == AffineTransform.TYPE_TRANSLATION) {
			r.x += (int)this.at.getTranslateX();
			r.y += (int)this.at.getTranslateY();
		} else {
			//r = transformRectangle(r);
			// transform points
			final double[] d1 = new double[]{0, 0, width, 0, width, height, 0, height};
			final double[] d2 = new double[8];
			this.at.transform(d1, 0, d2, 0, 4);
			// find min/max
			double min_x=Double.MAX_VALUE, min_y=Double.MAX_VALUE, max_x=-min_x, max_y=-min_y;
			for (int i=0; i<d2.length; i+=2) {
				if (d2[i] < min_x) min_x = d2[i];
				if (d2[i] > max_x) max_x = d2[i];
				if (d2[i+1] < min_y) min_y = d2[i+1];
				if (d2[i+1] > max_y) max_y = d2[i+1];
			}
			r.x = (int)min_x;
			r.y = (int)min_y;
			r.width = (int)(max_x - min_x);
			r.height = (int)(max_y - min_y);
		}
		return r;
	}

	/** Subclasses can override this method to provide the exact contour, otherwise it returns the transformed bounding box of the data. */
	public Polygon getPerimeter() {
		if (this.at.isIdentity() || this.at.getType() == AffineTransform.TYPE_TRANSLATION) {
			// return the bounding box as a polygon:
			final Rectangle r = getBoundingBox();
			return new Polygon(new int[]{r.x, r.x+r.width, r.x+r.width, r.x},
					   new int[]{r.y, r.y, r.y+r.height, r.y+r.height},
					   4);
		}
		// else, the rotated/sheared/scaled and translated bounding box:
		final double[] po1 = new double[]{0,0,  width,0,  width,height,  0,height};
		final double[] po2 = new double[8];
		this.at.transform(po1, 0, po2, 0, 4);
		return new Polygon(new int[]{(int)po2[0], (int)po2[2], (int)po2[4], (int)po2[6]},
				   new int[]{(int)po2[1], (int)po2[3], (int)po2[5], (int)po2[7]},
				   4);
	}

	/** Returns the perimeter enlarged in all West, North, East and South directions, in pixels.*/
	public Polygon getPerimeter(final int w, final int n, final int e, final int s) {
		if (this.at.isIdentity() || this.at.getType() == AffineTransform.TYPE_TRANSLATION) {
			// return the bounding box as a polygon:
			final Rectangle r = getBoundingBox();
			return new Polygon(new int[]{r.x -w, r.x+r.width +w+e, r.x+r.width +w+e, r.x -w},
					   new int[]{r.y -n, r.y -n, r.y+r.height +n+s, r.y+r.height +n+s},
					   4);
		}
		// else, the rotated/sheared/scaled and translated bounding box:
		final double[] po1 = new double[]{-w,-n,  width+w+e,-n,  width+w+e,height+n+s,  -w,height+n+s};
		final double[] po2 = new double[8];
		this.at.transform(po1, 0, po2, 0, 4);
		return new Polygon(new int[]{(int)po2[0], (int)po2[2], (int)po2[4], (int)po2[6]},
				   new int[]{(int)po2[1], (int)po2[3], (int)po2[5], (int)po2[7]},
				   4);
	}

	/** Test whether the given point falls within the perimeter of this Displayable, considering the position x,y. Used by the DisplayCanvas mouse events. */
	public boolean contains(final int x_p, final int y_p) {
		return getPerimeter().contains(x_p, y_p);
	}

	/** Calls contains(x_p, y_p) unless overriden -- in ZDisplayable objects, it tests whether the given point is contained in the part of the ZDisplayable that shows in the given layer. */
	public boolean contains(final Layer layer, final int x_p, final int y_p) {
		return contains(x_p, y_p);
	}

	public void setAlpha(float alpha) {
		setAlpha(alpha, true);
	}

	protected void setAlpha(float alpha, boolean update) {
		if (alpha != this.alpha && alpha >= 0.0f && alpha <= 1.0f) {
			this.alpha = alpha;
			if (update) {
				Display.repaint(layer, this, 5);
				updateInDatabase("alpha");
				Display3D.setTransparency(this, alpha);
			}
		}
	}

	public float getAlpha() { return alpha; }

	public Color getColor() { return color; }

	/** Return the HashSet of directly linked Displayable objects. */
	public HashSet<Displayable> getLinked() { return hs_linked; }

	/** Return those of Class c from among the directly linked. */
	public HashSet<Displayable> getLinked(final Class c) {
		if (null == hs_linked) return null;
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		for (final Displayable d : hs_linked) {
			if (d.getClass() == c) hs.add(d);
		}
		return hs;
	}

	/** Return the HashSet of all directly and indirectly linked objects. */
	public HashSet<Displayable> getLinkedGroup(HashSet<Displayable> hs) {
		if (null == hs) hs = new HashSet<Displayable>();
		else if (hs.contains(this)) return hs;
		hs.add(this);
		if (null == hs_linked) return hs;
		for (final Displayable d : hs_linked) {
			d.getLinkedGroup(hs);
		}
		return hs;
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		Utils.log2("mousePressed not implemented yet for " + this.getClass().getName());
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		Utils.log2("mouseDragged not implemented yet for " + this.getClass().getName());
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		Utils.log2("mouseReleased not implemented yet for " + this.getClass().getName());
	}

	public void keyPressed(KeyEvent ke) {

		int key_code = ke.getKeyCode();

		switch(key_code) {

		case KeyEvent.VK_ENTER: //End transform or bring ImageJ to front
			ke.consume();
			break;
		case KeyEvent.VK_UP: //move one pixel up
			translate(0, -1, true);
			ke.consume();
			break;
		case KeyEvent.VK_DOWN: //move one pixel down
			translate(0, 1, true);
			ke.consume();
			break;
		case KeyEvent.VK_LEFT: //move one pixel left
			translate(-1, 0, true);
			ke.consume();
			break;
		case KeyEvent.VK_RIGHT: //move one pixel right
			translate(1, 0, true);
			ke.consume();
			break;
		case KeyEvent.VK_ESCAPE:
			Display.setActive(ke, null);
			ke.consume();
			break;
		}

		Rectangle box = getLinkedBox(true);

		if (ke.isConsumed() && KeyEvent.VK_ESCAPE != key_code) {
			// no need to repaint the previous box, falls within 1 pixel (and here I add 5 on the margins)
			Display.repaint(this.layer, box, 5);
		} //not repainting for ESC because it has been done already
	}

	public boolean isVisible() { return this.visible; }

	public final void setVisible(boolean visible) {
		setVisible(visible, true);
	}

	public void setVisible(final boolean visible, final boolean repaint) {
		if (visible == this.visible) {
			// patching synch error
			Display.updateVisibilityCheckbox(layer, this, null);
			return;
		}
		this.visible = visible;
		if (repaint) {
			//Display.setUpdateGraphics(layer, this);
			Display.repaint(layer, this, 5);
		}
		updateInDatabase("visible");
		Display.updateVisibilityCheckbox(layer, this, null);
	}

	/** Repaint this Displayable in all Display instances that are showing it. */
	public void repaint() {
		Display.repaint(layer, this, 5);
	}

	public void setColor(Color color) {
		if (null == color || color.equals(this.color)) return;
		this.color = color;
		updateInDatabase("color");
		Display.repaint(layer, this, 5);
		Display3D.setColor(this, color);
	}

	/** Release resources. */
	public void destroy() {
		this.layer = null;
	}

	public boolean isOutOfRepaintingClip(final double magnification, final Rectangle srcRect, final Rectangle clipRect) {
		// 1 - check visibility
		if (!visible) {
			//if not visible, it's out, so return true:
			return true;
		}
		final Rectangle box = getBoundingBox(null); // includes rotation
		// 2 - check if out of clipRect (clipRect is in screen coords, whereas srcRect is in offscreen coords)
		if (null != clipRect && null != srcRect) {
			final int screen_x = (int)((box.x -srcRect.x) * magnification);
			final int screen_y = (int)((box.y -srcRect.y) * magnification);
			final int screen_width = (int)(box.width * magnification);
			final int screen_height = (int)(box.height * magnification);
			if ((screen_x + screen_width) < clipRect.x || (screen_y + screen_height) < clipRect.y || screen_x > (clipRect.x + clipRect.width) || screen_y > (clipRect.y + clipRect.height)) {
				return true;
			}
		}

		// 3 - check if out of srcRect
		// This is included above anyway, but just in case there is no clipRect !
		if (null != srcRect) {
			if (box.x + box.width < srcRect.x || box.y + box.height < srcRect.y || box.x > srcRect.x + srcRect.width || box.y > srcRect.y + srcRect.height) {
				return true;
			}
		}

		// else: it's in, so paint!
		return false;
	}

	/** For the DisplayNavigator. No srcRect or magnification considered. */
	public boolean isOutOfRepaintingClip(Rectangle clipRect, double scale) {
		// 1 - check visibility
		if (!visible) {
			//if not visible, it's out, so return true:
			return true;
		}
		Rectangle box = getBoundingBox(); // includes rotation
		// 2 - check if out of clipRect
		if (null != clipRect) {
			int screen_x = (int)(box.x * scale);
			int screen_y = (int)(box.y * scale);
			int screen_width = (int)(box.width * scale);
			int screen_height = (int)(box.height * scale);
			if ((screen_x + screen_width) < clipRect.x || (screen_y + screen_height) < clipRect.y || screen_x > (clipRect.x + clipRect.width) || screen_y > (clipRect.y + clipRect.height)) {
			return true;
			}
		}

		// else: it's in, so paint!
		return false;
	}

	/** Remove also from the trees if present; does nothing more than remove(boolean) unless overriden. */
	protected boolean remove2(final boolean check) {
		return remove(check);
	}

	/** Remove from both the database and any Display that shows the Layer in which this Displayable is shown. */
	public boolean remove(final boolean check) {
		if (super.remove(check) && layer.remove(this)) {
			unlink();
			removeLinkedPropertiesFromOrigins();
			Search.remove(this);
			Compare.remove(this);
			Display.flush(this);
			return true;
		}
		Utils.log("Failed to remove " + this.getClass().getName() + " " + this);
		return false;
	}

	/** Link the given Displayable with this Displayable, and then tell the given Displayable to link this. Since the link is stored as Displayable objects in a HashSet, there'll never be repeated entries. */
	public void link(final Displayable d) {
		link(d, true);
	}

	/** Link the given Displayable with this Displayable, and then tell the given Displayable to link this. Since the link is stored as Displayable objects in a HashSet, there'll never be repeated entries.*/
	public void link(final Displayable d, final boolean update_database) { // the boolean is used by the loader when reconstructing links.
		if (this == d) return;
		if (null == this.hs_linked) this.hs_linked = new HashSet<Displayable>();
		// link the other to this
		this.hs_linked.add(d);
		// link this to the other
		if (null == d.hs_linked) d.hs_linked = new HashSet();
		d.hs_linked.add(this);
		// update the database
		if (update_database) project.getLoader().addCrossLink(project.getId(), this.id, d.id);
	}

	/** Remove all links held by this Displayable.*/
	public void unlink() {
		if (null == this.hs_linked) return;
		final Displayable[] displ = new Displayable[hs_linked.size()];
		hs_linked.toArray(displ);

		// all these redundancy because of the [typical] 'concurrent modification exception'
		for (int i=0; i<displ.length; i++) {
			unlink(displ[i]);
		}
		this.hs_linked = null;
	}

	/** Remove the link with the given Displayable, and tell the given Displayable to remove the link with this. */
	public void unlink(final Displayable d) {
		//Utils.log("Displayable.unlink(Displayable)");
		if (this == d) {
			return; // should not happen
		}
		if (null == this.hs_linked) return; // should not happen
		// unlink the other from this, and this from the other
		if (!( hs_linked.remove(d) && d.hs_linked.remove(this))) {
			// signal database inconsistency (should not happen)
			Utils.log("Database inconsistency: two displayables had a non-reciprocal link. BEWARE of other errors.");
		}
		// update the database in any case
		project.getLoader().removeCrossLink(this.id, d.id);
	}

	/** Check if this object is directly linked to any other Displayable objects.*/
	public boolean isLinked() {
		if (null == hs_linked) return false;
		return !hs_linked.isEmpty();
	}

	/** Check if this object is directly linked to a Displayable object of the given Class. */
	public boolean isLinked(final Class c) {
		if (null == hs_linked) return false;
		for (final Displayable d : hs_linked) {
			if (c.isInstance(d)) return true;
		}
		return false;
	}

	/** Check if thisobject is directly linked to the given Displayable. */
	public boolean isLinked(final Displayable d) {
		if (null == hs_linked) return false;
		return hs_linked.contains(d);
	}

	/** Check if this object is directly linked only to Displayable objects of the given class (returns true) or to none (returns true as well).*/
	public boolean isOnlyLinkedTo(final Class c) {
		if (null == hs_linked || hs_linked.isEmpty()) return true;
		for (final Displayable d : hs_linked) {
			if (d.getClass() != c) return false;
		}
		return true;
	}

	/** Check if this object is directly linked only to Displayable objects of the given class in the same layer (returns true). Returns true as well when not linked to any of the given class.*/
	public boolean isOnlyLinkedTo(final Class c, final Layer layer) {
		if (null == hs_linked || hs_linked.isEmpty()) return true;
		for (final Displayable d : hs_linked) {
			// if the class is not the asked one, or the object is not in the same layer, return false!
			if (d.getClass() != c || d.layer != this.layer) return false;
		}
		return true;
	}


	/** Link the Patch objects that lay underneath the bounding box of this Displayable, so that they cannot be dragged independently. */
	public void linkPatches() {
		final String prop = project.getProperty(Project.getName(this.getClass()).toLowerCase() + "_nolinks");
		if (null != prop && prop.equals("true")) return;
		// find the patches that don't lay under other profiles of this profile's linking group, and make sure they are unlinked. This will unlink any Patch objects under this Profile:
		unlinkAll(Patch.class);

		// scan the Display and link Patch objects that lay under this Profile's bounding box:

		// catch all displayables of the current Layer
		final ArrayList al = layer.getDisplayables(Patch.class);

		// this bounding box:
		final Polygon perimeter = getPerimeter(); //displaced by this object's position!
		if (null == perimeter) return; // happens when a profile with zero points is deleted

		// for each Patch, check if it underlays this profile's bounding box
		Rectangle box = new Rectangle();
		for (Iterator itd = al.iterator(); itd.hasNext(); ) {
			final Displayable displ = (Displayable)itd.next();
			// stupid java, Polygon cannot test for intersection with another Polygon !! //if (perimeter.intersects(displ.getPerimeter())) // TODO do it yourself: check if a Displayable intersects another Displayable
			if (perimeter.intersects(displ.getBoundingBox(box))) {
				// Link the patch
				this.link(displ);
			}
		}
	}
	/** Unlink all Displayable objects of the given type linked by this. */
	public void unlinkAll(final Class c) {
		if (!this.isLinked() || null == hs_linked) {
			return;
		}
		// catch Displayables, or the iterators will go mad when deleting objects
		final Displayable[] displ = new Displayable[hs_linked.size()];
		hs_linked.toArray(displ);
		for (int i=0; i<displ.length; i++) {
			if (displ[i].getClass() == c) {
				unlink(displ[i]);
			}
		}
	}

	/** Check if this perimeter's intersects that of the given Displayable. */
	public boolean intersects(final Displayable d) {
		return intersects(new Area(d.getPerimeter()));
	}

	public boolean intersects(final Area area) {
		final Area a = new Area(getPerimeter());
		a.intersect(area);
		final Rectangle b = a.getBounds();
		return 0 != b.width && 0 != b.height;
	}

	/** Calls intersects(area) unless overriden -- intended for ZDisplayable objects to return whether they intersect the given area at the given layer. */
	public boolean intersects(final Layer layer, final Area area) {
		return intersects(area);
	}

	public boolean intersects(final Layer layer, final Rectangle r) {
		return getBoundingBox(null).intersects(r);
	}

	/** Returns the intersection of this Displayable's area with the given one. */
	public Area getIntersection(final Displayable d) {
		final Area a = new Area(this.getPerimeter());
		a.intersect(new Area(d.getPerimeter()));
		return a;
	}

	/** Returns the sum of bounding boxes of all linked Displayables. */
	public Rectangle getLinkedBox(final boolean same_layer) {
		if (null == hs_linked || hs_linked.isEmpty()) return getBoundingBox();
		final Rectangle box = new Rectangle();
		accumulateLinkedBox(same_layer, new HashSet(), box);
		return box;
	}

	/** Accumulates in the box. */
	private void accumulateLinkedBox(final boolean same_layer, final HashSet hs_done, final Rectangle box) {
		if (hs_done.contains(this)) return;
		hs_done.add(this);
		box.add(getBoundingBox(null));
		for (final Displayable d : hs_linked) {
			// add ZDisplayables regardless, for their 'layer' pointer is used to know which part of them must be painted.
			if (same_layer && !(d instanceof ZDisplayable) && d.layer != this.layer) continue;
			d.accumulateLinkedBox(same_layer, hs_done, box);
		}
	}

	/** Minimal info that identifies this object as unique, for display on a JTree node.*/
	public String toString() {
		return new StringBuffer(this.title.length() + 20).append(this.title).append(!(this instanceof ZDisplayable) && null != layer ? " z=" + layer.getZ() : "").append(' ').append('#').append(this.id).toString(); // the layer is null when recreating the object from the database and printing it for testing in the Loader
	}

	abstract public boolean isDeletable();

	/** Subclasses can specify the behaviour, for the default is true.*/
	public boolean canSendTo(Layer layer) {
		return true;
	}

	/** Does nothing unless overriden. */
	public void exportSVG(StringBuffer data, double z_scale, String indent) {}

	/** Does nothing unless overriden. Used for profile, pipe and ball points when preventing dragging beyond the screen, to snap to cursor when this reenters. */
	public void snapTo(int cx, int cy, int x_p, int y_p) {}

	/** Shows a dialog to adjust properties of this object. */
	public void adjustProperties() {
		GenericDialog gd = makeAdjustPropertiesDialog();
		gd.showDialog();
		if (gd.wasCanceled()) return;
		processAdjustPropertiesDialog(gd);
	}

	protected GenericDialog makeAdjustPropertiesDialog() {
		Rectangle box = getBoundingBox(null);
		GenericDialog gd = new GD("Properties", this);
		gd.addStringField("title: ", title);
		gd.addNumericField("x: ", box.x, 2);
		gd.addNumericField("y: ", box.y, 2);
		gd.addNumericField("scale_x: ", 1, 2);
		gd.addNumericField("scale_y: ", 1, 2);
		gd.addNumericField("rot (degrees): ", 0, 2);
		gd.addSlider("alpha: ", 0, 100, (int)(alpha*100));
		gd.addCheckbox("visible", visible);
		gd.addSlider("Red: ", 0, 255, color.getRed());
		gd.addSlider("Green: ", 0, 255, color.getGreen());
		gd.addSlider("Blue: ", 0, 255, color.getBlue());
		gd.addCheckbox("locked", locked);
		// add slider listener
		final Scrollbar alp = (Scrollbar)gd.getSliders().get(0);
		final Scrollbar red = (Scrollbar)gd.getSliders().get(1);
		final Scrollbar green = (Scrollbar)gd.getSliders().get(2);
		final Scrollbar blue = (Scrollbar)gd.getSliders().get(3);
		final TextField talp = (TextField)gd.getNumericFields().get(5);
		final TextField tred = (TextField)gd.getNumericFields().get(6);
		final TextField tgreen = (TextField)gd.getNumericFields().get(7);
		final TextField tblue = (TextField)gd.getNumericFields().get(8);
		SliderListener sla = new SliderListener() {
			public void update() {
				setAlpha((float)alp.getValue()/100);
			}
		};
		SliderListener slc = new SliderListener() {
			public void update() {
				setColor(new Color(red.getValue(), green.getValue(), blue.getValue()));
			}
		};
		alp.addAdjustmentListener(sla);
		red.addAdjustmentListener(slc);
		green.addAdjustmentListener(slc);
		blue.addAdjustmentListener(slc);
		talp.addTextListener(sla);
		tred.addTextListener(slc);
		tgreen.addTextListener(slc);
		tblue.addTextListener(slc);
		return gd;
	}

	private abstract class SliderListener implements AdjustmentListener, TextListener {
		public void adjustmentValueChanged(AdjustmentEvent ae) { update(); }
		public void textValueChanged(TextEvent te) { update(); }
		abstract public void update();
	}

	private class GD extends GenericDialog {
		Displayable displ;
		Color dcolor;
		float dalpha;
		GD(String title, Displayable displ) {
			super(title);
			this.displ = displ;
			this.dcolor = new Color(displ.color.getRed(), displ.color.getGreen(), displ.color.getBlue()); // can't clone color?
			this.dalpha = displ.alpha;
		}
		/** Override to restore original color when canceled. */
		public void dispose() {
			if (wasCanceled()) {
				displ.alpha = dalpha;
				displ.setColor(dcolor); // calls repaint
			}
			super.dispose();
		}
	}

	protected DoEdit processAdjustPropertiesDialog(final GenericDialog gd) {
		// store old transforms for undo
		HashSet<Displayable> hs = getLinkedGroup(new HashSet<Displayable>());
		layer.getParent().addTransformStep(hs);
		// store old box
		//Rectangle box = getLinkedBox(true);//getBoundingBox();

		final HashSet<String> fields = new HashSet<String>();

		// adjust values:
		String title1 = gd.getNextString();
		double x1 = gd.getNextNumber();
		double y1 = gd.getNextNumber();
		double sx = gd.getNextNumber();
		double sy = gd.getNextNumber();
		double rot1 = gd.getNextNumber();
		float alpha1 = (float)gd.getNextNumber() / 100;

		final DoEdit prev = new DoEdit(this);

		if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(sx) || Double.isNaN(sy) || Float.isNaN(alpha1)) {
			Utils.showMessage("Invalid values!");
			return null;
		}

		Color co = new Color((int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
		if (!co.equals(this.color)) {
			prev.add("color", color);
			color = co;
			updateInDatabase("color");
		}
		boolean visible1 = gd.getNextBoolean();
		boolean locked1 = gd.getNextBoolean();
		if (!title.equals(title1)) {
			prev.add("title", title1);
			setTitle(title1); // will update the panel
		}


		// Add the transforms, even if not modified (too much pain)
		if (null != hs) prev.add(new DoTransforms().addAll(hs));
		else prev.add("at", this.getAffineTransformCopy());

		final Rectangle b = getBoundingBox(null); // previous
		if (x1 != b.x || y1 != b.y) {
			if (null != hs) {
				// apply the scaling and displacement to all linked
				Rectangle box_old = getBoundingBox();
				// fix FreeHandProfile past errors
				if (0 == box_old.width || 0 == box_old.height) {
					if (this instanceof Profile) {
						((Profile)this).calculateBoundingBox(true);
						box_old = getBoundingBox();
					} else {
						//avoid division by zero
						Utils.showMessage("Some error ocurred: zero width or height ob the object to adjust.\nUnlink this object '" + this + "' and adjust carefully");
						return null;
					}
				}
				this.setLocation(x1, y1);
				Rectangle b2 = getBoundingBox(null);
				int dx = b2.x - b.x;
				int dy = b2.y - b.y;
				for (Iterator it = hs.iterator(); it.hasNext(); ) {
					Displayable d = (Displayable)it.next();
					if (this.equals(d)) continue;
					d.translate(dx, dy, false);
				}
			} else {
				this.setLocation(x1, y1);
			}
		}
		if (1 != sx || 1 != sy) {
			if (null != hs) {
				// scale all
				for (Iterator it = hs.iterator(); it.hasNext(); ) {
					Displayable d = (Displayable)it.next();
					d.scale(sx, sy, b.y+b.width/2, b.y+b.height/2, false); // centered on this
				}
			} else {
				this.scale(sx, sy, b.y+b.width/2, b.y+b.height/2, false);
			}
		}
		if (rot1 != 0) {
			// rotate relative to the center of he Displayable
			double rads = Math.toRadians(rot1);
			if (null != hs) {
				//Utils.log2("delta_angle, rot1, rot: " + delta_angle + "," + rot1 + "," + rot);
				for (Iterator it = hs.iterator(); it.hasNext(); ) {
					Displayable d = (Displayable)it.next();
					d.rotate(rads, b.x+b.width/2, b.y+b.height/2, false);
				}
			} else {
				this.rotate(rads, b.x+b.width/2, b.y+b.height/2, false);
			}
		}
		if (alpha1 != alpha) {
			prev.add("alpha", alpha1);
			setAlpha(alpha1, true);
		}
		if (visible1 != visible) {
			prev.add("visible", visible1);
			setVisible(visible1);
		}
		if (locked1 != locked) {
			prev.add("locked", locked1);
			setLocked(locked1);
		}

		getLayerSet().addEditStep(prev); // contains the transformations of all others, if necessary.

		// it's lengthy to predict the precise box for each open Display, so just repaint all in all Displays.
		Display.updateSelection();
		Display.repaint(getLayer()); // not this.layer, so ZDisplayables are repainted properly

		return prev;
	}

	static protected final String TAG_ATTR1 = "<!ATTLIST ";
	static protected final String TAG_ATTR2 = " NMTOKEN #REQUIRED>\n";

	/** Adds simply DTD !ATTRIBUTE tags. The ProjectThing that encapsulates this object will give the type. */
	static public void exportDTD(String type, StringBuffer sb_header, HashSet hs, String indent) {
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" oid").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" layer_id").append(TAG_ATTR2)
			 /*
			 .append(indent).append(TAG_ATTR1).append(type).append(" x").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" y").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" width").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" height").append(TAG_ATTR2)
			 */
			 .append(indent).append(TAG_ATTR1).append(type).append(" transform").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" style").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" locked").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" visible").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" title").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" links").append(TAG_ATTR2)
		;
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		if (!hs.contains("t2_prop")) {
			sb_header.append(indent).append("<!ELEMENT t2_prop EMPTY>\n")
				 .append(indent).append(TAG_ATTR1).append("t2_prop key").append(TAG_ATTR2)
			         .append(indent).append(TAG_ATTR1).append("t2_prop value").append(TAG_ATTR2)
			;
		}
		if (!hs.contains("t2_linked_prop")) {
			sb_header.append(indent).append("<!ELEMENT t2_linked_prop EMPTY>\n")
				 .append(indent).append(TAG_ATTR1).append("t2_linked_prop target_id").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_linked_prop key").append(TAG_ATTR2)
			         .append(indent).append(TAG_ATTR1).append("t2_linked_prop value").append(TAG_ATTR2)
			;
		}
	}

	static protected String commonDTDChildren() {
		return "t2_prop,t2_linked_prop"; // never commas at beginning or end, only in between
					  // never returns empty
	}

	/** The oid is this objects' id, whereas the 'id' tag will be the id of the wrapper Thing object. */ // width and height are used for the data itself, so that for example the image does not need to be loaded
	public void exportXML(final StringBuffer sb_body, final String in, final Object any) {
		final double[] a = new double[6];
		at.getMatrix(a);
		sb_body.append(in).append("oid=\"").append(id).append("\"\n")
			.append(in).append("width=\"").append(width).append("\"\n")
			.append(in).append("height=\"").append(height).append("\"\n")
			.append(in).append("transform=\"matrix(").append(a[0]).append(',')
								.append(a[1]).append(',')
								.append(a[2]).append(',')
								.append(a[3]).append(',')
								.append(a[4]).append(',')
								.append(a[5]).append(")\"\n")
		;
		// the default is obvious, so just store the value if necessary
		if (locked) sb_body.append(in).append("locked=\"true\"\n");
		if (!visible) sb_body.append(in).append("visible=\"false\"\n");
		// 'style' is taken care in subclasses
		if (null != title && title.length() > 0) {
			sb_body.append(in).append("title=\"").append(title.replaceAll("\"", "^#^")).append("\"\n"); // fix possible harm by '"' characters (backslash should be taken care of as well TODO)
		}
		sb_body.append(in).append("links=\"");
		if (null != hs_linked && 0 != hs_linked.size()) {
			// Sort the ids: so resaving the file saves an identical file (otherwise, ids are in different order).
			final long[] ids = new long[hs_linked.size()];
			int ii = 0;
			for (final Displayable d : hs_linked) ids[ii++] = d.id;
			Arrays.sort(ids);
			for (int g=0; g<ids.length; g++) sb_body.append(ids[g]).append(',');
			sb_body.setLength(sb_body.length()-1); // remove last comma by shifting cursor backwards
		}
		sb_body.append("\"\n");
	}

	/** Add properties, links, etc. Does NOT close the tag. */
	synchronized protected void restXML(final StringBuffer sb_body, final String in, final Object any) {
		// Properties:
		if (null != props && !props.isEmpty()) {
			for (final Map.Entry<String,String> e : props.entrySet()) {
				final String value = e.getValue();
				if (null == value) continue; // impossible, but with reflection one may set it so
				sb_body.append(in).append("<t2_prop key=\"").append(e.getKey()).append("\" value=\"").append(cleanAttr(e, value)).append("\" />\n");
			}
		}
		if (null != linked_props && !linked_props.isEmpty()) {
			for (final Map.Entry<Displayable,Map<String,String>> et : linked_props.entrySet()) {
				final Displayable target = et.getKey();
				for (final Map.Entry<String,String> e : et.getValue().entrySet()) {
					final String value = e.getValue();
					if (null == value) continue; // impossible, but with reflection one may set it so
					sb_body.append(in).append("<t2_linked_prop target_id=\"").append(target.id).append("\" key=\"").append(e.getKey()).append("\" value=\"").append(cleanAttr(e, value)).append("\" />\n");
				}
			}
		}
	}

	// Make sure the value is valid for an XML attribute content inside double quotes.
	final private String cleanAttr(final Map.Entry<String,String> e, String value) {
		if (-1 != value.indexOf('"')) {
			Utils.log("Property " + e.getKey() + " for ob id=#" + this.id + " contains a \" which is being replaced by '.");
			value = value.replace('"', '\'');
		}
		if (-1 != value.indexOf('\n')) {
			Utils.log("Property " + e.getKey() + " for ob id=#" + this.id + " contains a newline char which is being replaced by a space.");
			value = value.replace('\n', ' ');
		}
		return value;
	}

	// I'm sure it could be made more efficient, but I'm too tired!
	public boolean hasLinkedGroupWithinLayer(Layer la) {
		HashSet hs = getLinkedGroup(new HashSet());
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (!d.layer.equals(la)) return false;
		}
		return true;
	}

	/** Rotate 2D points relative to the given pivot point by the given rot angle (in radians), in the 2D plane. */
	static public double[][] rotatePoints(double[][] p, double rot, double xo, double yo) {
		//Utils.log("calling rotatePoints for " + p + "  with rot=" + Math.toDegrees(rot));
		if (null == p) {
			Utils.log2("WARNING: Displayable.rotatePoints received a null points array.");
			return new double[0][0];
		}
		int length = p[0].length;
		double[][] pr = new double[p.length][length];
		for (int i=0; i<length; i++) {
			// catch for readability
			double x = p[0][i];
			double y = p[1][i];
			// angle relative to the pivot point
			double b1 = Utils.getAngle(x - xo, y - yo);
			// new angle relative to pivot point
			double b2 = b1 + rot;
			// new location
			double hypot = Math.sqrt((x - xo)*(x - xo) + (y - yo)*(y - yo));
			pr[0][i] = xo + (Math.cos(b2) * hypot); //x + (Math.cos(b2) - Math.cos(b1)) * hypot;
			pr[1][i] = yo + (Math.sin(b2) * hypot); //y + (Math.sin(b2) - Math.sin(b1)) * hypot;
		}
		return pr;
	}

	/** Scale 2D points relative to the given pivot point xo,yo. */
	static public double[][] scalePoints(double[][] p, double sx, double sy, double xo, double yo) {
		int length = p[0].length;
		double[][] ps = new double[p.length][length];
		for (int i=0; i<length; i++) {
			ps[0][i] = xo + (p[0][i] - xo) * sx;
			ps[1][i] = yo + (p[1][i] - yo) * sy;
		}
		return ps;
	}

	static public double[][] displacePoints(double[][] p, double dx, double dy) {
		int length = p[0].length;
		double[][] pd = new double[p.length][length];
		for (int i=0; i<length; i++) {
			pd[0][i] = p[0][i] + dx;
			pd[1][i] = p[1][i] + dy;
		}
		return pd;
	}

	/** Transform in place only the 'i' point in the points array.*/
	static public void transformPoint(double[][] p, int i, double dx, double dy, double rot, double xo, double yo) {
		p[0][i] += dx;
		p[1][i] += dy;
		if (0 != rot) {
			double hypot = Math.sqrt(Math.pow(p[0][i] - xo, 2) + Math.pow(p[1][i] - yo, 2));
			double angle = Utils.getAngle(p[0][i] - xo, p[1][i] - yo);
			p[0][i] = xo + Math.cos(angle + rot) * hypot;
			p[1][i] = yo + Math.sin(angle + rot) * hypot;
		}
	}

	/** Fine nearest point in array a, from 0 up to n_points, to point x_p,y_p.
	 * @return the index of such point. */
	static protected int findNearestPoint(final double[][] a, final int n_points, final double x_p, final double y_p) {
		if (0 == n_points) return -1;
		double min_dist = Double.MAX_VALUE;
		int index = -1;
		for (int i=0; i<n_points; i++) {
			double sq_dist = Math.pow(a[0][i] - x_p, 2) + Math.pow(a[1][i] - y_p, 2);
			if (sq_dist < min_dist) {
				index = i;
				min_dist = sq_dist;
			}
		}
		return index;
	}

	/** Performs a deep copy of this object. */
	public Object clone() {
		return clone(this.project);
	}

	/** Performs a deep copy of this object, obtaining its unique id either from the given project or the exact same as this object's id. The visibility, though, is set to true at all times. */
	abstract public Displayable clone(Project pr, boolean copy_id);

	/** Performs a deep copy of this object but assigning to it the given project. The visibility, though, is set to true at all times. */
	public Displayable clone(Project pr) {
		return clone(pr, false);
	}

	public LayerSet getLayerSet() {
		if (null != layer) return layer.getParent();
		return null;
	}

	public boolean updateInDatabase(String key) {
		// ???? TODO ???? cruft from the past?  // project.getLoader().updateCache(this, key);
		//if (Utils.java3d) Display3D.update(this);
		return super.updateInDatabase(key);
	}

	static public Rectangle getMinimalBoundingBox(Displayable[] d) {
		final Rectangle box = d[0].getBoundingBox();
		final Rectangle tmp = new Rectangle();
		for (int i=1; i<d.length; i++) {
			box.add(d[i].getBoundingBox(tmp));
		}
		return box;
	}

	public AffineTransform getAffineTransform() {
		return at;
	}

	public AffineTransform getAffineTransformCopy() {
		return (AffineTransform)at.clone();
	}

	/** Sets the matrix values of this Displayable's AffineTransform to those of the given AffineTransform. */
	public void setAffineTransform(AffineTransform at) {
		this.at.setTransform(at);
		updateInDatabase("transform");
		updateBucket();
	}

	/** Translate this Displayable and its linked ones if linked=true. */
	public void translate(double dx, double dy, boolean linked) {
		if (Double.isNaN(dx) || Double.isNaN(dy)) return;
		final AffineTransform at2 = new AffineTransform();
		at2.translate(dx, dy);
		preTransform(at2, linked);
	}

	public void translate(double dx, double dy) {
		translate(dx, dy, true);
	}

	/** Rotate relative to an anchor point. */
	public void rotate(double radians, double xo, double yo) {
		rotate(radians, xo, yo, true);
	}

	/** Rotate relative to an anchor point. */
	public void rotate(double radians, double xo, double yo, boolean linked) {
		if (Double.isNaN(radians) || Double.isNaN(xo) || Double.isNaN(yo)) return;
		final AffineTransform at2 = new AffineTransform();
		at2.rotate(radians, xo, yo);
		preTransform(at2, linked);
	}

	/** Commands the parent container (a Layer or a LayerSet) to update the bucket position of this Displayable. */
	public void updateBucket() {
		if (null != getBucketable()) getBucketable().updateBucket(this);
	}

	/** Scale relative to an anchor point (will translate as necessary). */
	public void scale(double sx, double sy, double xo, double yo) {
		scale(sx, sy, xo, yo, true);
	}

	/** Scale relative to an anchor point (will translate as necessary). */
	public void scale(double sx, double sy, double xo, double yo, boolean linked) {
		if (Double.isNaN(sx) || Double.isNaN(sy) || Double.isNaN(xo) || Double.isNaN(yo)) return;
		final AffineTransform at2 = new AffineTransform();
		at2.translate( xo, yo );
		at2.scale( sx, sy );
		at2.translate( -xo, -yo );
		preTransform(at2, linked);
	}

	/** Sets the top left of the bounding box to x,y. Warning: does not check that the object will remain within layer bounds. Does NOT affect linked Displayables. */
	public void setLocation(double x, double y) {
		if (Double.isNaN(x) || Double.isNaN(y)) return;
		Rectangle b = getBoundingBox(null);
		this.translate(x - b.x, y - b.y, false); // do not affect linked Displayables
		//Utils.log2("setting new loc, args are: " + x + ", "+ y);
		updateBucket();
	}

	/** Apply this Displayable's AffineTransform to the given point. */
	public Point2D.Double transformPoint(final double px, final double py) {
		final Point2D.Double pSrc = new Point2D.Double(px, py);
		if (this.at.isIdentity()) return pSrc;
		final Point2D.Double pDst = new Point2D.Double();
		this.at.transform(pSrc, pDst);
		return pDst;
	}

	public Point2D.Double inverseTransformPoint(final double px, final double py) {
		final Point2D.Double pSrc = new Point2D.Double(px, py);
		if (this.at.isIdentity()) return pSrc;
		final Point2D.Double pDst = new Point2D.Double();
		try {
			this.at.createInverse().transform(pSrc, pDst);
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
		}
		return pDst;
	}

	/** Returns a new Rectangle which encloses completly the given rectangle after transforming it with this Displayable's AffineTransform. The given rectangle's fields are untouched.*/
	final public Rectangle transformRectangle(final Rectangle r) {
		if (this.at.isIdentity()) return (Rectangle)r.clone();
		return new Area(r).createTransformedArea(this.at).getBounds();
	}

	/** Returns the argument if this Displayable's AffineTransform is the identity; otherwise returns a new double[][] with all points from @param p transformed according to the AffineTransform. The  double[][] array provided as argument is expected to be of type [2][length], i.e. two arrays describing x and y, and it is left intact.
	*/
	public double[][] transformPoints(final double[][] p) {
		return transformPoints(p, p[0].length);
	}

	/** Will crop second dimension of the given array at the given length. */
	protected double[][] transformPoints(final double[][] p, final int length) {
		if (this.at.isIdentity()) return p;
		//final int length = p[0].length;
		final double[] p2a = new double[length * 2];
		for (int i=0, j=0; i<length; i++, j+=2) {
			p2a[j] = p[0][i];
			p2a[j+1] = p[1][i];
		}
		final double[] p2b = new double[length * 2];
		this.at.transform(p2a, 0, p2b, 0, length); // what a silly format: consecutive x,y numbers! Clear case of premature optimization.
		final double[][] p3 = new double[2][length];
		for (int i=0, j=0; i<length; i++, j+=2) {
			p3[0][i] = p2b[j];
			p3[1][i] = p2b[j+1];
		}
		return p3;
	}

	/** Concatenate the given affine to this and all its linked objects. */
	public void transform(final AffineTransform at) {
		for (Iterator it = getLinkedGroup(new HashSet()).iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			d.at.concatenate(at);
			d.updateInDatabase("transform");
			d.updateBucket();
			//Utils.log("applying transform to " + d);
		}
	}

	/** preConcatenate the given affine transform to this Displayable's affine. */
	public void preTransform(final AffineTransform affine, final boolean linked) {
		if (linked) {
			for (Iterator it = getLinkedGroup(null).iterator(); it.hasNext(); ) {
				final Displayable d = (Displayable)it.next();
				d.at.preConcatenate(affine);
				d.updateInDatabase("transform");
				d.updateBucket();
			}
		} else {
			this.at.preConcatenate(affine);
			this.updateInDatabase("transform");
			this.updateBucket();
		}
	}

	public void paintAsBox(final Graphics2D g) {
		final double[] c = new double[]{0,0,  width,0,  width,height,  0,height};
		final double[] c2 = new double[8];
		this.at.transform(c, 0, c2, 0, 4);
		g.setColor(color);
		g.drawLine((int)c2[0], (int)c2[1], (int)c2[2], (int)c2[3]);
		g.drawLine((int)c2[2], (int)c2[3], (int)c2[4], (int)c2[5]);
		g.drawLine((int)c2[4], (int)c2[5], (int)c2[6], (int)c2[7]);
		g.drawLine((int)c2[6], (int)c2[7], (int)c2[0], (int)c2[1]);
	}

	public void paintSnapshot(final Graphics2D g, final double mag) {
		switch (layer.getParent().getSnapshotsMode()) {
			case 0:
				paint(g, mag, false, 0xffffffff, layer);
				return;
			case 1:
				paintAsBox(g);
				return;
			default: return; // case 2: // disabled, no paint
		}
	}

	public DBObject findById(final long id) {
		if (this.id == id) return this;
		return null;
	}

	/** Does nothing unless overriden. */
	public ResultsTable measure(ResultsTable rt) {
		Utils.showMessage("Not implemented yet for " + Project.getName(getClass()) + " [class " + this.getClass().getName() + "]");
		return rt;
	}

	public Bucketable getBucketable() {
		return this.layer;
	}

	/** If the title is purely numeric, returns it as a double; otherwise returns 0. */
	protected double getNameId() {
		double nameid = 0;
		if (null != this.title) {
			try {
				nameid = Double.parseDouble(this.title.trim());
			} catch (NumberFormatException nfe) {}
		}
		return nameid;
	}

	// UNDO machinery
	
	class DoEdits implements DoStep {
		final HashSet<DoEdit> edits = new HashSet<DoEdit>();
		DoEdits(final Set<Displayable> col) {
			for (final Displayable d : col) {
				edits.add(new DoEdit(d));
			}
		}
		public Displayable getD() { return null; }
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof DoEdits)) return false;
			final DoEdits other = (DoEdits) ob;
			if (this.edits.size() != other.edits.size()) return false;
			final Iterator<DoEdit> it1 = this.edits.iterator();
			final Iterator<DoEdit> it2 = other.edits.iterator();
			// Order matters: (but it shouldn't!) TODO
			for (; it1.hasNext() && it2.hasNext(); ) {
				if ( ! it1.next().isIdenticalTo(it2.next())) return false;
			}
			return true;
		}
		public void init(final String[] fields) {
			for (final DoEdit edit : edits) {
				edit.init(edit.d, fields);
			}
		}
		public boolean isEmpty() { return edits.isEmpty(); }
		public boolean apply(int action) {
			boolean failed = false;
			for (final DoEdit edit : edits) {
				if (!edit.apply(action)) {
					failed = true;
				}
			}
			return !failed;
		}
	}

	/** For any Displayable data, including: title, visible, locked, color, alpha,
	 *  and a 'data' type which includes the actual data (points, areas, etc.) and the links,width,height, and transformation (since all the latter are correlated).*/
	class DoEdit implements DoStep {
		private final HashMap<String,Object> content = new HashMap<String,Object>();
		private ArrayList<DoStep> dependents = null;
		private final Displayable d;
		/** Returns self on success, otherwise null. */
		DoEdit(final Displayable d) {
			this.d = d;
		}
		public boolean containsKey(final String field) {
			return content.containsKey(field);
		}
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof DoEdit)) return false;
			final DoEdit other = (DoEdit) ob;
			// same Displayable?
			if (this.d != other.d) return false;
			// dependents?
			if (null != dependents) {
				// TODO
				return false;
			}
			// same number of fields to edit?
			if (this.content.size() != other.content.size()) return false;
			// any data? Currently comparisons of data are disabled
			if (null != this.content.get("data") || null != other.content.get("data")) {
				return false;
			}
			// same content of fields?
			for (final Map.Entry<String,Object> e : this.content.entrySet()) {
				final Object val = other.content.get(e.getKey());
				if (val instanceof HashMap) {
					if ( ! identical((HashMap)val, (HashMap)e.getValue())) {
						return false;
					}
				} else if ( ! val.equals(e.getValue())) return false;
			}
			return true;
		}
		private boolean identical(final HashMap m1, final HashMap m2) {
			if (m1.size() != m2.size()) return false;
			for (final Object entry : m1.entrySet()) {
				final Map.Entry e = (Map.Entry) entry;
				// TODO this could fail if value is null
				if ( ! e.getValue().equals(m2.get(e.getKey()))) return false;
			}
			return true;
		}
		synchronized public Displayable getD() { return d; }
		synchronized DoEdit fullCopy() {
			return init(d, new String[]{"data", "width", "height", "locked", "title", "color", "alpha", "visible", "props", "linked_props"});
		}
		/** With the same keys as 'de'. */
		synchronized DoEdit init(final DoEdit de) {
			return init(de.d, de.content.keySet().toArray(new String[0]));
		}
		synchronized public boolean add(final DoStep step) {
			if (null == dependents) dependents = new ArrayList<DoStep>();
			if (dependents.contains(step)) return false;
			dependents.add(step);
			return true;
		}
		synchronized public boolean add(final String field, final Object value) {
			content.put(field, value);
			return true;
		}
		synchronized DoEdit init(final Displayable d, final String[] fields) {
			final Class[] c = new Class[]{Displayable.class, d.getClass(), ZDisplayable.class};
			for (int k=0; k<fields.length; k++) {
				if ("data".equals(fields[k])) {
					content.put(fields[k], d.getDataPackage());
				} else {
					boolean got_it = false;
					for (int i=0; i<c.length; i++) {
						try {
							java.lang.reflect.Field f = c[i].getDeclaredField(fields[k]);
							if (null == f) continue; // will throw a NoSuchFieldException, but just in case
							f.setAccessible(true);
							Object ob = f.get(d);
							content.put(fields[k], null != ob ? duplicate(ob, fields[k]) : null);
							got_it = true;
						} catch (NoSuchFieldException nsfe) {
						} catch (IllegalAccessException iae) {}
					}
					if (!got_it) {
						Utils.log2("ERROR: could not get '" + fields[k] + "' field for " + d);
						return null;
					}
				}
			}
			return this;
		}
		/** Java's clone() is useless. */ // I HATE this imperative, fragile, ridiculous language that forces me to go around in circles and O(n) approaches when all I need is a PersistentHashMap with structural sharing, a clone() that WORKS ALWAYS, and metaprogramming abilities aka macros @#$%!
		private final Object duplicate(final Object ob, final String field) {
			if (ob instanceof Color) {
				final Color c = (Color)ob;
				return new Color(c.getRed(), c.getGreen(), c.getBlue());
			} else if (ob instanceof HashMap) {
				if (field.equals("linked_props")) {
					final HashMap hm = new HashMap();
					for (final Object e : ((HashMap)ob).entrySet()) {
						final Map.Entry me = (Map.Entry)e;
						hm.put(me.getKey(), ((HashMap)me.getValue()).clone());
					}
					return hm;
				}
				return new HashMap((HashMap)ob);
			}
			// Number, Boolean, String are all final classes:
			return ob;
		}
		/** Set the stored data to the stored Displayable. */
		public boolean apply(int action) {
			final Class[] c = new Class[]{Displayable.class, d.getClass(), ZDisplayable.class};
			for (final Map.Entry<String,Object> e : content.entrySet()) {
				String field = e.getKey();
				if ("data".equals(field)) {
					if (!d.setDataPackage((DataPackage)e.getValue())) {
						return false;
					}
				} else {
					try {
						for (int i=0; i<c.length; i++) {
							java.lang.reflect.Field f = c[i].getDeclaredField(field);
							f.setAccessible(true);
							f.set(d, e.getValue());
						}
					} catch (NoSuchFieldException nsfe) {
					} catch (IllegalAccessException iae) {
					} catch (Exception ex) {
						IJError.print(ex);
						return false;
					}
				}
			}
			boolean ok = true;
			if (null != dependents) {
				for (final DoStep step : dependents) {
					if (!step.apply(action)) ok = false;
				}
			}
			Display.update(d.getLayerSet());
			return ok;
		}
		public boolean isEmpty() {
			return null == d || (content.isEmpty() && (null == dependents || dependents.isEmpty()));
		}
	}

	protected class DoTransforms implements DoStep {
		final private HashMap<Displayable,AffineTransform> ht = new HashMap<Displayable,AffineTransform>();
		final HashSet<Layer> layers = new HashSet<Layer>();

		DoTransforms addAll(final Collection<Displayable> col) {
			for (final Displayable d : col) {
				ht.put(d, d.getAffineTransformCopy());
				layers.add(d.getLayer());
			}
			return this;
		}
		public boolean isEmpty() {
			return null == ht || ht.isEmpty();
		}
		public boolean apply(int action) {
			if (isEmpty()) return false;
			for (final Map.Entry<Displayable,AffineTransform> e : ht.entrySet()) {
				e.getKey().at.setTransform(e.getValue());
			}
			for (final Layer layer : layers) {
				layer.recreateBuckets();
			}
			if (!layers.isEmpty()) layers.iterator().next().getParent().recreateBuckets(false);
			return true;
		}
		public Displayable getD() { return null; }

		public boolean isIdenticalTo(final Object ob) {
			if (ob instanceof Collection) {
				final Collection<Displayable> col = (Collection<Displayable>) ob;
				if (ht.size() != col.size()) return false;
				for (final Displayable d : col) {
					if (!d.getAffineTransform().equals(ht.get(d))) {
						return false;
					}
				}
				return true;
			} else if (ob instanceof DoTransforms) {
				final DoTransforms dt = (DoTransforms) ob;
				if (dt.ht.size() != this.ht.size()) return false;
				for (final Map.Entry<Displayable,AffineTransform> e : this.ht.entrySet()) {
					if ( ! e.getValue().equals(dt.ht.get(e.getKey()))) {
						return false;
					}
				}
				return true;
			}
			return false;
		}
	}

	synchronized final boolean setDataPackage(final Displayable.DataPackage pkg) {
		if (pkg.getClass() != getInternalDataPackageClass()) {
			Utils.log2("ERROR: cannot set " + pkg.getClass() + " to " + this.getClass());
			return false;
		}
		try {
			return pkg.to2(this);

		} catch (Exception e) {
			IJError.print(e);
			return false;
		}
	}

	// Must be overriden by subclasses
	Object getDataPackage() {
		Utils.log2("Displayable.getDataPackage not implemented yet for " + getClass());
		return null;
	}
	// Must be overriden by subclasses
	Class getInternalDataPackageClass() {
		return DataPackage.class;
	}

	static abstract protected class DataPackage {
		protected final double width, height;
		protected final AffineTransform at;
		protected HashMap<Displayable,HashSet<Displayable>> links = null;

		DataPackage(final Displayable d) {
			this.width = d.width;
			this.height = d.height;
			this.at = new AffineTransform(d.at);
			if (null != d.hs_linked) {
				this.links = new HashMap<Displayable,HashSet<Displayable>>();
				for (final Displayable ln : d.hs_linked) {
					this.links.put(ln, new HashSet<Displayable>(ln.hs_linked));
				}
			}
		}

		/** Set the Displayable's fields. */
		final boolean to1(final Displayable d) {
			d.width = width;
			d.height = height;
			d.setAffineTransform(at); // updates bucket
			if (null != links) {
				for (final Map.Entry<Displayable,HashSet<Displayable>> e : links.entrySet()) {
					e.getKey().hs_linked = e.getValue();
				}
			}
			return true;
		}
		// Could simply use a single 'to' method (it works, tested),
		// but if I ever was to cast inadvertendly to Displayable, then
		// only the superclass' 'to' method would be invoked, not the
		// subclass' one! I call it "defensive programming"
		/** Set the subclass specific data fields. */
		abstract boolean to2(final Displayable d);
	}
}
