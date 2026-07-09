import { useCallback, useEffect, useRef, useState } from 'react'
import { TravelData } from '../types'
import { loadData, saveData } from './storage'

export interface TravelActions {
  toggleState: (abbr: string) => void
  toggleInterstate: (route: string, state: string) => void
  setInterstateAll: (route: string, states: string[], visited: boolean) => void
  toggleCapitol: (abbr: string) => void
  toggleCountry: (key: string) => void
  replaceAll: (data: TravelData) => void
}

export function useTravelData(): [TravelData, TravelActions] {
  const [data, setData] = useState<TravelData>(loadData)

  const first = useRef(true)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    saveData(data)
  }, [data])

  const toggleState = useCallback((abbr: string) => {
    setData((d) => ({ ...d, states: toggleKey(d.states, abbr) }))
  }, [])

  const toggleCapitol = useCallback((abbr: string) => {
    setData((d) => ({ ...d, capitols: toggleKey(d.capitols, abbr) }))
  }, [])

  const toggleCountry = useCallback((key: string) => {
    setData((d) => ({ ...d, countries: toggleKey(d.countries, key) }))
  }, [])

  const toggleInterstate = useCallback((route: string, state: string) => {
    setData((d) => {
      const current = d.interstates[route] ?? []
      const next = current.includes(state)
        ? current.filter((s) => s !== state)
        : [...current, state].sort()
      const interstates = { ...d.interstates }
      if (next.length === 0) delete interstates[route]
      else interstates[route] = next
      return { ...d, interstates }
    })
  }, [])

  const setInterstateAll = useCallback((route: string, states: string[], visited: boolean) => {
    setData((d) => {
      const interstates = { ...d.interstates }
      if (visited) interstates[route] = [...states].sort()
      else delete interstates[route]
      return { ...d, interstates }
    })
  }, [])

  const replaceAll = useCallback((next: TravelData) => setData(next), [])

  return [data, { toggleState, toggleInterstate, setInterstateAll, toggleCapitol, toggleCountry, replaceAll }]
}

function toggleKey(map: Record<string, boolean>, key: string): Record<string, boolean> {
  const next = { ...map }
  if (next[key]) delete next[key]
  else next[key] = true
  return next
}
