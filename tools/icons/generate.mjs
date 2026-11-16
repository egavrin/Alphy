// Writes ToolIcons.kt: the domain icons that generated apps can use, as 24-unit stroke paths from Lucide (ISC license).
// Usage: copy this file into an empty directory outside the repository, and run in that directory:
//   bun add lucide-static@0.460.0
//   bun generate.mjs > <repo>/app/src/main/java/com/alpha/assistant/tools/ToolIcons.kt
import {readFileSync} from 'node:fs';

// App icon name -> Lucide icon file.
const icons = {
  Coffee: 'coffee', Cup: 'cup-soda', Drop: 'droplet', Glass: 'glass-water', Milk: 'milk', Utensils: 'utensils',
  Chef: 'chef-hat', Apple: 'apple', Carrot: 'carrot', Salad: 'salad', Pizza: 'pizza', Fish: 'fish', Bean: 'bean',
  Cake: 'cake-slice', IceCream: 'ice-cream-cone', Candy: 'candy', Wine: 'wine',
  Heart: 'heart', Pulse: 'activity', Dumbbell: 'dumbbell', Bike: 'bike', Steps: 'footprints', Person: 'person-standing',
  Pill: 'pill', Moon: 'moon', Bed: 'bed', Flame: 'flame', Weight: 'weight', Thermometer: 'thermometer',
  Stethoscope: 'stethoscope', Brain: 'brain', Smile: 'smile',
  Leaf: 'leaf', Sprout: 'sprout', Flower: 'flower', Tree: 'tree-pine', Sun: 'sun', Rain: 'cloud-rain', Snow: 'snowflake',
  Wind: 'wind', Umbrella: 'umbrella', Mountain: 'mountain', Globe: 'globe',
  Wallet: 'wallet', Card: 'credit-card', Coins: 'coins', Money: 'banknote', Bank: 'landmark', Savings: 'piggy-bank',
  Bag: 'shopping-bag', Tag: 'tag', Receipt: 'receipt', Percent: 'percent', Chart: 'chart-column', Trend: 'trending-up', Gift: 'gift',
  Hourglass: 'hourglass', Book: 'book-open', Pencil: 'pencil', School: 'graduation-cap', Idea: 'lightbulb', Language: 'languages',
  Puzzle: 'puzzle', Music: 'music', Headphones: 'headphones', Paint: 'palette', Picture: 'image', Film: 'film',
  Home: 'house', Car: 'car', Bus: 'bus', Plane: 'plane', Train: 'train-front', Map: 'map', Compass: 'compass',
  Luggage: 'luggage', Tent: 'tent', Key: 'key', Lamp: 'lamp', Wrench: 'wrench', Hammer: 'hammer', Sofa: 'sofa',
  Bath: 'bath', Shower: 'shower-head', Laundry: 'washing-machine', Plug: 'plug', Battery: 'battery', Wifi: 'wifi',
  Phone: 'phone', Mail: 'mail', Message: 'message-circle', People: 'users', User: 'user', Baby: 'baby', Dog: 'dog',
  Cat: 'cat', Paw: 'paw-print', Briefcase: 'briefcase',
  Trophy: 'trophy', Star: 'star', Medal: 'medal', Dice: 'dice-5', Gamepad: 'gamepad-2', Target: 'target', Flag: 'flag',
  Rocket: 'rocket', Crown: 'crown',
  Share: 'share-2', Sort: 'arrow-up-down', List: 'list', Checklist: 'list-checks', Eye: 'eye', Lock: 'lock',
  Warning: 'triangle-alert', Help: 'circle-help', Volume: 'volume-2', Bookmark: 'bookmark', Package: 'package',
  Shirt: 'shirt', Scissors: 'scissors', Ruler: 'ruler',
};

const number = (value) => Number(value ?? 0);
const attrs = (text) => Object.fromEntries([...text.matchAll(/([a-z0-9-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]]));
const n = (value) => String(Math.round(value * 1000) / 1000);
function ellipse(cx, cy, rx, ry) {
  return `M${n(cx - rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx + rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx - rx)} ${n(cy)}Z`;
}
function rect(a) {
  const x = number(a.x), y = number(a.y), w = number(a.width), h = number(a.height);
  const rx = Math.min(number(a.rx ?? a.ry), w / 2), ry = Math.min(number(a.ry ?? a.rx), h / 2);
  if (!rx) return `M${n(x)} ${n(y)}H${n(x + w)}V${n(y + h)}H${n(x)}Z`;
  return `M${n(x + rx)} ${n(y)}H${n(x + w - rx)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + w)} ${n(y + ry)}V${n(y + h - ry)}` +
    `A${n(rx)} ${n(ry)} 0 0 1 ${n(x + w - rx)} ${n(y + h)}H${n(x + rx)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x)} ${n(y + h - ry)}` +
    `V${n(y + ry)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + rx)} ${n(y)}Z`;
}
function points(list, close) {
  const values = list.trim().split(/[\s,]+/).map(Number);
  let path = '';
  for (let i = 0; i < values.length; i += 2) path += `${i ? 'L' : 'M'}${n(values[i])} ${n(values[i + 1])}`;
  return close ? path + 'Z' : path;
}
// The first moveto of an SVG path is absolute even when it is a lowercase m, and its later pairs are relative lines.
// The paths are joined into one, so this start must be written as an absolute M.
function absoluteStart(d) {
  const start = d.match(/^m\s*(-?[\d.]+)[\s,]*(-?[\d.]+)[\s,]*/);
  if (!start) return d;
  const rest = d.slice(start[0].length);
  return `M${start[1]} ${start[2]}` + (/^[-\d.]/.test(rest) ? 'l' : '') + rest;
}
function convert(file) {
  const svg = readFileSync(`node_modules/lucide-static/icons/${file}.svg`, 'utf8');
  return [...svg.matchAll(/<(path|circle|ellipse|rect|line|polyline|polygon)\b([^>]*)\/?>/g)].map(([, tag, text]) => {
    const a = attrs(text);
    switch (tag) {
      case 'path': return absoluteStart(a.d.replace(/\s+/g, ' ').trim());
      case 'circle': return ellipse(number(a.cx), number(a.cy), number(a.r), number(a.r));
      case 'ellipse': return ellipse(number(a.cx), number(a.cy), number(a.rx), number(a.ry));
      case 'rect': return rect(a);
      case 'line': return `M${n(number(a.x1))} ${n(number(a.y1))}L${n(number(a.x2))} ${n(number(a.y2))}`;
      case 'polyline': return points(a.points, false);
      case 'polygon': return points(a.points, true);
    }
  }).join('');
}
const lines = Object.entries(icons).map(([name, file]) => `        "${name}" to "${convert(file)}",`);
console.log(`// Generated by tools/icons/generate.mjs from Lucide 0.460.0 (ISC license, assets/licenses/lucide.txt). Do not edit by hand.
package com.alpha.assistant.tools

/** Domain icons for generated apps: 24-unit stroke paths, drawn like the shell glyphs. */
internal object ToolIcons {
    val paths: Map<String, String> = mapOf(
${lines.join('\n')}
    )
}`);
